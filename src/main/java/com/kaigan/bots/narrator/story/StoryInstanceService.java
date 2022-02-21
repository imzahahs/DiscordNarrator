package com.kaigan.bots.narrator.story;

import com.kaigan.bots.narrator.Narrator;
import com.kaigan.bots.narrator.NarratorService;
import com.kaigan.bots.narrator.ProcessedMessage;
import com.kaigan.bots.narrator.SheetMessageBuilder;
import delight.nashornsandbox.NashornSandbox;
import delight.nashornsandbox.NashornSandboxes;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.MessageBuilder;
import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.message.react.MessageReactionAddEvent;
import net.dv8tion.jda.api.events.message.react.MessageReactionRemoveEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.script.ScriptException;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

public class StoryInstanceService implements NarratorService {
    private static final Logger log = LogManager.getLogger("StoryInstanceService");

    private enum StartStatus {
        WAITING,
        PREPARING,
        NOT_ENOUGH_RESOURCES,
        STARTING
    }

    final StoryService storyService;
    private final MessageChannel initiateChannel;
    private final Member initiateMember;
    private final String storyId;

    StoryService.StoryInfo storyInfo;
    StoryBuilder builder;

    private Message introMessage;
    private List<Emote> introChoiceEmotes;
    private Emote cancelEmote;

    // Story runtime
    final Map<String, StoryBot> storyBots = new HashMap<>();
    final Map<String, StoryChannelService> channels = new HashMap<>();
    final ScriptState states = new ScriptState();
    final Map<String, Member> players = new HashMap<>();
    final Map<Member, String> playerNameLookup = new HashMap<>();

    float chatTimingMultiplier = 1f;

    private long tIntroInviteTimeout = Long.MAX_VALUE;
    private long tIntroConcludedTimeout = Long.MAX_VALUE;
    private long tInstanceTimeout = Long.MAX_VALUE;

    private StartStatus status = StartStatus.WAITING;

    // Script engine
    private ScriptInterface scriptEngine = null;
    final Narrator.FormatResolver formatResolver = identifier -> {
        if(identifier.startsWith("@")) {
            Object value = states.get(identifier.substring(1), null);
            if(value != null)
                return value.toString();
            return null;
        }
        return null;
    };


    public void eval(String js) {
        if(status != StartStatus.STARTING || players.isEmpty())
            throw new IllegalStateException("cannot eval while instance is not ready or shutting down");

        if(scriptEngine == null)
            scriptEngine = new ScriptInterface(this);

        scriptEngine.eval(js);
    }


    void resetInstanceTimeout() {
        if(players.isEmpty())
            return;         // ignore timeout refreshes if no player (instance is shutting down)
        resetInstanceTimeout(storyService.config.instanceTimeout);
    }

    private void resetInstanceTimeout(long timeout) {
        tInstanceTimeout = System.currentTimeMillis() + timeout;
    }

    public void reset(String[] tags) {
        for(StoryChannelService channel : channels.values()) {
            channel.reset();
        }
        states.clear();
        if (tags != null) {
            for (String tag : tags)
                states.set(tag, true);
        }
    }

    public void playerEnding(String[] names, int color, String message) {
        StringBuilder sb = new StringBuilder();
        // Remove player first
        for(String name : names) {
            // Resolve player
            name = name.toLowerCase();
            Member participant = players.get(name);
            if(participant != null) {
                // Remove participant from all channels
                for(StoryChannelService channel : channels.values())
                    channel.removePlayer(name);
                // Unrecognize player
                players.remove(name);
                playerNameLookup.remove(participant);
                if(sb.length() > 0)
                    sb.append(", ");
                sb.append(participant.getAsMention());
            }
            else
                log.error("Unable to resolve player {} for ending", name);
        }

        // Shutdown if no more players
        if(players.isEmpty()) {
            // Queue ending timeout
            resetInstanceTimeout(storyService.config.instanceQuitDelay);
        }

        // Publish message if available
        if(message != null && sb.length() > 0) {
            storyService.config.endingMessage.embed.color = color;
            storyService.bot.queue(() -> storyService.config.endingMessage.build(storyService.bot, initiateChannel,
                    "id", storyInfo.id,
                    "title", builder.title,
                    "message", storyService.bot.format(message,
                            "participant", sb.toString(),
                            formatResolver
                    )
            ), log, "Publishing ending message");
        }
    }

    StoryInstanceService(StoryService storyService, MessageChannel initiateChannel, Member initiateMember, String storyId) {
        this.storyService = storyService;
        this.initiateChannel = initiateChannel;
        this.initiateMember = initiateMember;
        this.storyId = storyId;
    }

    @Override
    public long onServiceStart(Narrator bot) {
        if(introMessage != null)
            throw new IllegalStateException("already started");     // UB

        // Get specified story
        storyInfo = storyService.findStory(storyId);

        if(storyInfo == null) {
            // Story not found
            bot.queue(() -> storyService.config.storyNotFoundMessage.select().build(bot, initiateChannel,
                    "sender", initiateMember.getAsMention(),
                    "code", storyId
            ), log, "Send story not found message");

            // Remove
            bot.removeService(this);
            return -1;
        }

        // Check if player has already joined another instance
        StoryInstanceService existing = bot.getServices(StoryInstanceService.class)
                .filter(instance -> instance != this && instance.players.containsValue(initiateMember))
                .findAny().orElse(null);
        if(existing != null) {
            // If this instance is still waiting, remove player and add to this invite
            if(existing.status == StartStatus.WAITING) {
                // Remove player from that instance and refresh
                existing.players.values().remove(initiateMember);
                existing.playerNameLookup.remove(initiateMember);
                existing.refreshIntroMessage();
            }
            else {
                // Else prev instance already started
                // Ignore request to create new invite
                bot.removeService(this);
                return -1;
            }
        }

        // Load story
        builder = storyService.loadStory(storyInfo);

        // Prepare for tracking
        introChoiceEmotes = new ArrayList<>();
        for (int c = 0; c < builder.players.length; c++) {
            Emote emote = storyService.bot.getChoiceEmote(c).get();
            introChoiceEmotes.add(emote);
        }
        cancelEmote = storyService.bot.getEmote("no").get();

        // Send intro message
        refreshIntroMessage();

        // Monitor timeouts
        return storyService.config.storyChannelTimestep;
    }

    @Override
    public long processService(Narrator bot) {
        long currentTime = System.currentTimeMillis();

        // Intro concluded (success or failed), delete the message after a while
        if(currentTime > tIntroConcludedTimeout) {
            tIntroConcludedTimeout = Long.MAX_VALUE;
            // Delete message
            introMessage.delete().queue();          // ignore result
            if(status != StartStatus.STARTING) {
                // If havent successfully started, stop service
                bot.removeService(this);
                return -1;      // ended
            }
        }

        // Invite timed out due to inactivity, delete the message and stop service
        if(currentTime > tIntroInviteTimeout) {
            // Delete message
            introMessage.delete().queue();          // ignore result
            bot.removeService(this);
            return -1;
        }

        // Instance timeout
        if(currentTime > tInstanceTimeout) {
            if(tIntroConcludedTimeout != Long.MAX_VALUE)
                introMessage.delete().queue();          // ignore result
            shutdownInstance();
            bot.removeService(this);
            return -1;
        }

        return storyService.config.storyChannelTimestep;
    }

    @Override
    public boolean processMessage(Narrator bot, MessageReceivedEvent event, ProcessedMessage message) {
        if(event.getChannel() != initiateChannel)
            return false;       // not monitored

        processInstanceCommands(bot, event, message);

        return false;
    }

    boolean processInstanceCommands(Narrator bot, MessageReceivedEvent event, ProcessedMessage message) {
        Optional<String[]> commands = storyService.parseCommands(message.raw);
        if(commands.isPresent()) {
            // Received commands
            String[] parameters = commands.get();

            // Only recognize if players sent it
            if(!players.containsValue(event.getMember()))
                return false;       // ignore

            if(parameters.length == 2 && parameters[0].equalsIgnoreCase(storyService.config.instanceSpeedCommand)) {
                // Parse duration number
                try {
                    float speed = Float.parseFloat(parameters[1]);
                    if(speed > 0) {
                        // Valid speed parameter, but only respond if its the owner of the story
                        if(event.getMember().getId().contentEquals(storyInfo.owner)) {
                            chatTimingMultiplier = 1f / speed;
                            log.info("Setting speed to {} for instance {}", speed, builder.title);
                        }
                    }
                } catch (Throwable e) {
                    // ignore
                }

                return true;
            }

            if(parameters.length == 1 && parameters[0].equalsIgnoreCase(storyService.config.instanceQuitCommand)) {
                // Player wants to quit, only allow if all story channels are idle
                boolean isActive = channels.values().stream()
                        .map(StoryChannelService::isIdle)
                        .anyMatch(isIdle -> !isIdle);
                if(!isActive) {
                    // Okay, send message to all channels of quit request
                    for (StoryChannelService channel : channels.values()) {
                        SheetMessageBuilder quitMessage = storyService.config.instanceQuitMessage.select();
                        bot.queue(() -> quitMessage.build(bot, channel.channel,
                                "participant", event.getMember().getAsMention()
                        ), log, "Inform quit request");
                    }
                    // Queue quit timeout
                    if(event.getMember().getId().contentEquals(storyInfo.owner))
                        resetInstanceTimeout(0);           // if owner, quit immediately
                    else
                        resetInstanceTimeout(storyService.config.instanceQuitDelay);
                }
                else {
                    // Okay, send message to all channels of failing to quit
                    for (StoryChannelService channel : channels.values()) {
                        SheetMessageBuilder quitMessage = storyService.config.instanceQuitFailedMessage.select();
                        bot.queue(() -> quitMessage.build(bot, channel.channel,
                                "participant", event.getMember().getAsMention()
                        ), log, "Inform quit failed request");
                    }
                }
                return true;
            }

            // Unknown or malformed command
            return true;
        }

        return false;
    }

    @Override
    public boolean processReactionAdded(Narrator bot, MessageReactionAddEvent event) {
        if(introMessage == null || !event.getMessageId().equals(introMessage.getId()) || status != StartStatus.WAITING)
            return false;       // not monitored

        // Check if trying to close lobby
        if(event.getReactionEmote().getEmote() == cancelEmote) {
            // Only acknowledge if its the sender
            if(event.getMember().equals(initiateMember))
                introMessage.delete().queue();      // acknowledge intent to close
            bot.removeService(this);
            return false;
        }

        // Check if trying to join a player slot
        int index = introChoiceEmotes.indexOf(event.getReactionEmote().getEmote());
        if(index != -1) {
            // Chose a player slot, ignore if already occupied
            String nameProperCase = builder.players[index].name;
            String name = nameProperCase.toLowerCase();
            if(players.containsKey(name))
                return false;       // ignore as someone already selected this slot
            // Check if already joined as another player
            if(players.containsValue(event.getMember()) && !event.getMember().getId().equals(storyInfo.owner))
                return false;       // already joined and not the owner, only allow owner to play as multiple players for testing
            // Else check if has joined another instance
            StoryInstanceService existing = bot.getServices(StoryInstanceService.class)
                    .filter(instance -> instance != this && instance.players.containsValue(event.getMember()))
                    .findAny().orElse(null);
            if(existing != null) {
                // If this instance is still waiting, remove player and add to this invite
                if (existing.status == StartStatus.WAITING) {
                    // Remove player from that instance and refresh
                    existing.players.values().remove(initiateMember);
                    existing.playerNameLookup.remove(initiateMember);
                    existing.refreshIntroMessage();
                } else
                    return false;       // already joined another instance, wait for it to finish first
            }

            // Else can join
            players.put(name, event.getMember());
            playerNameLookup.put(event.getMember(), nameProperCase);

            // Attempt to prepare story and refresh intro message
            attemptStartStory();
        }


        return false;
    }

    @Override
    public boolean processReactionRemoved(Narrator bot, MessageReactionRemoveEvent event) {
        if(introMessage == null || !event.getMessageId().equals(introMessage.getId()) || status != StartStatus.WAITING)
            return false;       // not monitored

        // Check if trying to leave a player slot
        int index = introChoiceEmotes.indexOf(event.getReactionEmote().getEmote());
        if(index != -1) {
            // Remove if same player occupied
            String name = builder.players[index].name.toLowerCase();
            Member player = players.get(name);
            if(!event.getMember().equals(player))
                return false;       // not the same person, ignore

            // Else remove user from player slot
            players.remove(name);
            playerNameLookup.remove(player);

            // Refresh intro message
            refreshIntroMessage();
        }

        return false;
    }

    private void attemptStartStory() {
        if(players.size() < builder.players.length) {
            refreshIntroMessage();
            return;         // not enough players
        }

        // Clear reactions
        introMessage.clearReactions().queue();

        // Else can try to prepare story
        status = StartStatus.PREPARING;
        refreshIntroMessage();

        // Prepare story runtime
        try {
            // Acquire all bots
            Stream.concat(Optional.ofNullable(builder.npcs).stream().flatMap(Arrays::stream), Arrays.stream(builder.players))
                    .map(npc -> storyService.requestBot(this, npc).orElseThrow(() -> new RuntimeException("Unable to acquire bot: " + npc.name)))
                    .forEach(storyBot -> storyBots.put(storyBot.getName().toLowerCase(), storyBot));

            // Create all channels
            for(StoryChannelBuilder channelBuilder : builder.channels) {
                StoryChannelService channelService = new StoryChannelService(this, channelBuilder);
                storyService.bot.addService(channelService);
                channels.put(channelBuilder.name, channelService);
            }

            for(StoryChannelBuilder channelBuilder : builder.channels) {
                states.set(channelBuilder.name + ".main", true);
            }

            status = StartStatus.STARTING;

            resetInstanceTimeout();
        } catch (Throwable e) {
            log.error("Unable to prepare story", e);

            // Failed to prepare, release all resources
            shutdownInstance();

            status = StartStatus.NOT_ENOUGH_RESOURCES;
        }

        // Refresh outcome
        refreshIntroMessage();

        // Remove intro message after a while
        tIntroInviteTimeout = Long.MAX_VALUE;
        tIntroConcludedTimeout = System.currentTimeMillis() + storyService.config.introConcludedTimeout;
    }

    private void refreshIntroMessage() {
        // Format intro message
        StoryService.Config.IntroMessageConfig format = storyService.config.intro;

        Narrator narrator = storyService.bot;
        StringBuilder sb = new StringBuilder();

        MessageBuilder messageBuilder = new MessageBuilder();

        // Intro embed
        EmbedBuilder embedBuilder = new EmbedBuilder()
                .setTitle(narrator.format(format.title,
                        "title", builder.title,
                        "id", storyInfo.id
                ));

        // Header
        sb.append(narrator.format(format.description,
                "author", builder.author,
                "description", builder.description
        ));

        sb.append("\n\n");

        // Assemble choices
        for(int c = 0; c < builder.players.length; c++) {
            Member participant = players.get(builder.players[c].name.toLowerCase());
            StoryBuilder.PlayerBuilder playerBuilder = builder.players[c];

            if(c > 0)
                sb.append("\n\n");

            if(participant != null) {
                sb.append(narrator.format(format.playerJoined,
                        "choiceEmote", introChoiceEmotes.get(c).getAsMention(),
                        "name", playerBuilder.name,
                        "description", playerBuilder.description,
                        "player", participant.getAsMention()
                ));
            }
            else {
                sb.append(narrator.format(format.playerWaiting,
                        "choiceEmote", introChoiceEmotes.get(c).getAsMention(),
                        "name", playerBuilder.name,
                        "description", playerBuilder.description
                ));
            }
        }

        // Status

        // Status
        switch (status) {
            case PREPARING:
                sb.append("\n\n");
                sb.append(narrator.format(format.status.preparing));
                embedBuilder.setColor(format.status.preparingColor);
                break;

            case NOT_ENOUGH_RESOURCES:
                sb.append("\n\n");
                sb.append(narrator.format(format.status.notEnoughResources));
                embedBuilder.setColor(format.status.notEnoughResourcesColor);
                break;

            case STARTING:
                sb.append("\n\n");
                sb.append(narrator.format(format.status.starting));
                embedBuilder.setColor(format.status.startingColor);
                break;

            default:
            case WAITING:
                // nothing
                break;
        }

        embedBuilder.setDescription(sb.toString());
        messageBuilder.setEmbeds(embedBuilder.build());

        if(introMessage == null) {
            // Send new message
            introMessage = initiateChannel.sendMessage(messageBuilder.build()).complete();

            // Choice emotes
            for(Emote emote : introChoiceEmotes)
                storyService.bot.queue(() -> introMessage.addReaction(emote), log, "Adding choice emote to intro message");
            // Cancel emote
            storyService.bot.queue(() -> introMessage.addReaction(cancelEmote), log, "Adding cancel emote to intro message");
        }
        else {
            // Else just edit existing intro message
            storyService.bot.queue(() -> introMessage.editMessage(messageBuilder.build()), log, "Refresh intro message");
        }

        // Queue idle timeout
        tIntroInviteTimeout = System.currentTimeMillis() + storyService.config.introInviteTimeout;
    }

    private void shutdownInstance() {
        // Release all acquired bots
        for(StoryBot storyBot : storyBots.values())
            storyBot.release(this);
        storyBots.clear();

        // Release all channels
        for(StoryChannelService channel : channels.values()) {
            storyService.bot.removeService(channel);
        }
        channels.clear();

        players.clear();
        playerNameLookup.clear();

        // Shutdown script engine
        if(scriptEngine != null) {
            scriptEngine.scheduler.shutdownNow();
            scriptEngine = null;
        }
    }
}
