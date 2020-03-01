package com.kaigan.bots.narrator.story;

import com.kaigan.bots.narrator.Narrator;
import com.kaigan.bots.narrator.NarratorService;
import com.kaigan.bots.narrator.ProcessedMessage;
import net.dv8tion.jda.api.events.message.guild.GuildMessageReceivedEvent;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import sengine.sheets.SheetFields;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public class StoryService implements NarratorService {
    private static final Logger log = LogManager.getLogger("StoryService");

    private static final String GOOGLE_SHEET_URL = "https://docs.google.com/spreadsheets/d/";

    @SheetFields(requiredFields = { "monitoredChannels", "names" })
    public static class Config {
        public String[] monitoredChannels;
        public String[] names;

    }


    private final Narrator bot;
    private Config config;

    private Set<String> monitoredChannels;
    private Set<String> names;

    public void setConfig(Config config) {
        this.config = config;

        monitoredChannels = Arrays.stream(config.monitoredChannels)
                .map(bot::resolveTextChannel)
                .filter(Optional::isPresent)
                .map(optional -> optional.get().getId())
                .collect(Collectors.toCollection(HashSet::new));

        names = Arrays.stream(config.names)
                .map(String::toLowerCase)
                .collect(Collectors.toCollection(HashSet::new));
    }

    public StoryService(Narrator bot, Config config) {
        this.bot = bot;

        setConfig(config);
    }


    @Override
    public boolean processMessage(Narrator bot, GuildMessageReceivedEvent event, ProcessedMessage message) {
        // Only process monitored channels
        if(!monitoredChannels.contains(event.getChannel().getId()))
            return false;       // not monitored

        // Check if mentioned
        boolean hasMentioned = Arrays.stream(message.words).anyMatch(word -> names.contains(word));

        if(!hasMentioned)
            return false;       // not monitored

        // Check if sent google sheet url
        String googleSheetUrl = Arrays.stream(message.words)
                .filter(word -> word.startsWith(GOOGLE_SHEET_URL))
                .findFirst().orElse(null);
        if(googleSheetUrl != null) {
            String id = googleSheetUrl.substring(GOOGLE_SHEET_URL.length()).split("/")[0];

            log.info("Received upload request: " + id);

            // Delete message to protect story source code
            event.getMessage().delete().queue();

            return false;
        }

        // Check if sent story id
        String storyId = Arrays.stream(message.words)
                .filter(StringUtils::isNumeric)
                .findFirst().orElse(null);
        if(storyId != null) {
            log.info("Received story request: " + storyId);

            return false;
        }

        return false;
    }
}
