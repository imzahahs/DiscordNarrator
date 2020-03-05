package com.kaigan.bots.narrator.story;

import com.kaigan.bots.narrator.Narrator;
import com.kaigan.bots.narrator.NarratorService;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.events.message.guild.GuildMessageReceivedEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

class StoryInstanceService implements NarratorService {
    private static final Logger log = LogManager.getLogger("StoryInstanceService");

    private final StoryService storyService;
    private final GuildMessageReceivedEvent receivedMessage;
    private final String storyId;

    private Message introMessage;

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
        StoryService.StoryInfo info = storyService.findStory(storyId);

        if(info == null) {
            // Story not found
            bot.queue(() -> storyService.config.storyNotFoundMessage.select().build(bot, receivedMessage.getChannel(),
                    "sender", receivedMessage.getAuthor().getAsMention(),
                    "code", storyId
            ), log, "Send story not found message");

            return -1;
        }

        // Load story
        StoryBuilder builder = storyService.loadStory(info);

        // Send intro message
        StoryService.Config.IntroMessageConfig format = storyService.config.intro;
        EmbedBuilder embedBuilder = new EmbedBuilder()
                .setTitle(bot.format(format.title,
                        "title", builder.title
                ))
                .setColor(format.status.waitingColor)
                .setDescription(bot.format(format.description,
                        "author", builder.author,
                        "description", builder.description
                ));

        for(int c = 0; c < builder.players.length; c++) {
            embedBuilder.addField(
                    bot.format(format.player.title,
                            "choiceEmote", bot.getChoiceEmote(c).get().getAsMention(),
                            "name", builder.players[c].name
                    ),
                    format.player.waiting,
                    false
            );
        }

        introMessage = receivedMessage.getChannel().sendMessage(embedBuilder.build()).complete();
        for(int c = 0; c < builder.players.length; c++)
            introMessage.addReaction(bot.getChoiceEmote(c).get()).queue();

        // Delete existing message
        receivedMessage.getMessage().delete().queue();

        return -1;
    }
}
