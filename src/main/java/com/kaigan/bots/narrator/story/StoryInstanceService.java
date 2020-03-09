package com.kaigan.bots.narrator.story;

import com.kaigan.bots.narrator.Narrator;
import com.kaigan.bots.narrator.NarratorService;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Emote;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.events.message.guild.GuildMessageReceivedEvent;
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
    private final GuildMessageReceivedEvent receivedMessage;
    private final String storyId;

    private StoryService.StoryInfo storyInfo;
    private StoryBuilder builder;

    private Message introMessage;
    private List<Emote> introChoiceEmotes;
    private Emote cancelEmote;

    // Story runtime
    final Map<String, StoryBot> storyBots = new HashMap<>();
    final Map<String, StoryChannelService> channels = new HashMap<>();
    final ScriptState states = new ScriptState();
    final Map<String, Member> players = new HashMap<>();

    float chatTimingMultiplier = 1f;

    private StartStatus status = StartStatus.WAITING;

    StoryInstanceService(StoryService storyService, GuildMessageReceivedEvent receivedMessage, String storyId) {
        this.storyService = storyService;
        this.receivedMessage = receivedMessage;
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
            bot.queue(() -> storyService.config.storyNotFoundMessage.select().build(bot, receivedMessage.getChannel(),
                    "sender", receivedMessage.getAuthor().getAsMention(),
                    "code", storyId
            ), log, "Send story not found message");

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

        return -1;
    }

    @Override
    public boolean processReactionAdded(Narrator bot, GuildMessageReactionAddEvent event) {
        if(introMessage == null || !event.getMessageId().equals(introMessage.getId()) || status != StartStatus.WAITING)
            return false;       // not monitored

        // Check if trying to close lobby
        if(event.getReactionEmote().getEmote() == cancelEmote) {
            // Only acknowledge if its the sender
            if(event.getMember().equals(receivedMessage.getMember()))
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
            }

            // TODO: wait x seconds before starting
            for(StoryChannelBuilder channelBuilder : builder.channels) {
                states.set(channelBuilder.name + ".main", true);
            }

            status = StartStatus.STARTING;
        } catch (Throwable e) {
            log.error("Unable to prepare story", e);

            // Failed to prepare, release all resources
            for(StoryBot storyBot : storyBots.values())
                storyBot.release(this);
            storyBots.clear();

            status = StartStatus.NOT_ENOUGH_RESOURCES;
        }

        // Refresh outcome
        refreshIntroMessage();
    }

    private void refreshIntroMessage() {
        // Format intro message
        StoryService.Config.IntroMessageConfig format = storyService.config.intro;
        EmbedBuilder embedBuilder = new EmbedBuilder()
                .setTitle(storyService.bot.format(format.title,
                        "title", builder.title
                ))
                .setColor(format.status.waitingColor)
                .setDescription(storyService.bot.format(format.description,
                        "author", builder.author,
                        "description", builder.description
                ));

        for(int c = 0; c < builder.players.length; c++) {
            Member participant = players.get(builder.players[c].name.toLowerCase());
            StoryBuilder.PlayerBuilder playerBuilder = builder.players[c];

            if(participant != null) {
                embedBuilder.addField(
                        storyService.bot.format(format.playerJoined.title,
                                "choiceEmote", introChoiceEmotes.get(c).getAsMention(),
                                "name", playerBuilder.name
                        ),
                        storyService.bot.format(format.playerJoined.detail,
                                "description", playerBuilder.description,
                                "player", participant.getAsMention()
                        ),
                        false
                );
            }
            else {
                embedBuilder.addField(
                        storyService.bot.format(format.playerWaiting.title,
                                "choiceEmote", introChoiceEmotes.get(c).getAsMention(),
                                "name", playerBuilder.name
                        ),
                        storyService.bot.format(format.playerWaiting.detail,
                                "description", playerBuilder.description
                        ),
                        false
                );
            }
        }

        // Status
        switch (status) {
            case PREPARING:
                embedBuilder.addField(
                        storyService.bot.format(format.status.title),
                        storyService.bot.format(format.status.preparing),
                        false
                );
                embedBuilder.setColor(format.status.preparingColor);
                break;

            case NOT_ENOUGH_RESOURCES:
                embedBuilder.addField(
                        storyService.bot.format(format.status.title),
                        storyService.bot.format(format.status.notEnoughResources),
                        false
                );
                embedBuilder.setColor(format.status.notEnoughResourcesColor);
                break;

            case STARTING:
                embedBuilder.addField(
                        storyService.bot.format(format.status.title),
                        storyService.bot.format(format.status.starting),
                        false
                );
                embedBuilder.setColor(format.status.startingColor);
                break;

            default:
            case WAITING:
                embedBuilder.setColor(format.status.waitingColor);
                break;
        }

        if(introMessage == null) {
            // Send new message
            introMessage = receivedMessage.getChannel().sendMessage(embedBuilder.build()).complete();

            // Choice emotes
            for(Emote emote : introChoiceEmotes)
                storyService.bot.queue(() -> introMessage.addReaction(emote), log, "Adding choice emote to intro message");
            // Cancel emote
            storyService.bot.queue(() -> introMessage.addReaction(cancelEmote), log, "Adding cancel emote to intro message");
        }
        else {
            // Else just edit existing intro message
            storyService.bot.queue(() -> introMessage.editMessage(embedBuilder.build()), log, "Refresh intro message");
        }
    }
}
