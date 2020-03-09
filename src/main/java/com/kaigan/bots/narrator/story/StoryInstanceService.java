package com.kaigan.bots.narrator.story;

import com.kaigan.bots.narrator.Narrator;
import com.kaigan.bots.narrator.NarratorService;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.MessageBuilder;
import net.dv8tion.jda.api.entities.Emote;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.TextChannel;
import net.dv8tion.jda.api.events.message.guild.react.GuildMessageReactionAddEvent;
import net.dv8tion.jda.api.events.message.guild.react.GuildMessageReactionRemoveEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.*;
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
    private final TextChannel initiateChannel;
    private final Member initiateMember;
    private final Message buildMessage;
    private final String storyId;

    private StoryService.StoryInfo storyInfo;
    private StoryBuilder builder;

    private String introUploadSuccessFormat;
    private Message introMessage;
    private List<Emote> introChoiceEmotes;
    private Emote cancelEmote;

    // Story runtime
    final Map<String, StoryBot> storyBots = new HashMap<>();
    final Map<String, StoryChannelService> channels = new HashMap<>();
    final ScriptState states = new ScriptState();
    final Map<String, Member> players = new HashMap<>();

    float chatTimingMultiplier = 1f;

    private long tIntroInviteTimeout = Long.MAX_VALUE;
    private long tIntroConcludedTimeout = Long.MAX_VALUE;
    private long tInstanceTimeout = Long.MAX_VALUE;

    private StartStatus status = StartStatus.WAITING;

    void resetInstanceTimeout() {
        tInstanceTimeout = System.currentTimeMillis() + storyService.config.instanceTimeout;
    }

    StoryInstanceService(StoryService storyService, TextChannel initiateChannel, Member initiateMember, Message buildMessage, String storyId) {
        this.storyService = storyService;
        this.initiateChannel = initiateChannel;
        this.initiateMember = initiateMember;
        this.buildMessage = buildMessage;
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
        boolean hasJoinedAnotherInstance = bot.getServices(StoryInstanceService.class)
                .anyMatch(instance -> instance != this && instance.players.containsValue(initiateMember));
        if(hasJoinedAnotherInstance) {
            // If uploaded build message, just send success response without invite
            if(buildMessage != null) {
                bot.queue(() -> initiateChannel.sendMessage(bot.format(introUploadSuccessFormat,
                        "sender", initiateMember.getAsMention(),
                        "code", storyId
                )), log, "Sending upload success message without invite");
            }
            // Ignore request to create new invite
            bot.removeService(this);
            return -1;
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
            shutdownInstance();
            bot.removeService(this);
            return -1;
        }

        return storyService.config.storyChannelTimestep;
    }

    @Override
    public boolean processReactionAdded(Narrator bot, GuildMessageReactionAddEvent event) {
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
            String name = builder.players[index].name.toLowerCase();
            if(players.containsKey(name))
                return false;       // ignore as someone already selected this slot
            // Check if already joined as another player
            if(players.containsValue(event.getMember()) && !event.getMember().getId().equals(storyInfo.owner))
                return false;       // already joined and not the owner, only allow owner to play as multiple players for testing
            // Else check if has joined another instance
            boolean hasJoinedAnotherInstance = bot.getServices(StoryInstanceService.class)
                    .anyMatch(instance -> instance != this && instance.players.containsValue(event.getMember()));
            if(hasJoinedAnotherInstance)
                return false;       // already joined another instance, wait for it to finish first

            // Else can join
            players.put(name, event.getMember());

            // Attempt to prepare story and refresh intro message
            attemptStartStory();
        }


        return false;
    }

    @Override
    public boolean processReactionRemoved(Narrator bot, GuildMessageReactionRemoveEvent event) {
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

        // Build acknowledgement message
        if(buildMessage != null) {
            if(introUploadSuccessFormat == null)
                introUploadSuccessFormat = storyService.config.uploadSuccessMessage.select();
            messageBuilder.append(narrator.format(introUploadSuccessFormat,
                    "sender", initiateMember.getAsMention(),
                    "code", storyId
            ));
        }

        // Intro embed
        EmbedBuilder embedBuilder = new EmbedBuilder()
                .setTitle(narrator.format(format.title,
                        "title", builder.title
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
        messageBuilder.setEmbed(embedBuilder.build());

        if(introMessage == null) {
            // Send new message
            if(buildMessage != null)
                introMessage = buildMessage.editMessage(messageBuilder.build()).complete();       // Edit existing build message
            else
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
    }
}
