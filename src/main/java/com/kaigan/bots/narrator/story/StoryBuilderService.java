package com.kaigan.bots.narrator.story;//package com.kaigan.bots.discordia.services.story;

import com.kaigan.bots.narrator.Narrator;
import com.kaigan.bots.narrator.NarratorService;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageChannel;
import net.dv8tion.jda.api.entities.User;
import okhttp3.Request;
import okhttp3.Response;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import sengine.sheets.ParseException;
import sengine.sheets.SheetParser;

import java.util.Locale;
import java.util.concurrent.Future;

class StoryBuilderService implements NarratorService {
    private static final Logger log = LogManager.getLogger("StoryBuilderService");

    private static final String GOOGLE_XLSX_URL = "https://docs.google.com/spreadsheets/d/%s/export?format=xlsx";
    private static final String GOOGLE_XLSX_SUBTYPE = "vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private static final long LOAD_CHECK_INTERVAL = 250;

    private final StoryService storyService;

    private final MessageChannel channel;
    private final User user;

    private final String docId;

    private Message statusMessage;

    private Future<StoryBuilder> loadTask;

    StoryBuilderService(StoryService storyService, MessageChannel channel, User user, String docId) {
        this.storyService = storyService;

        this.channel = channel;
        this.user = user;

        this.docId = docId;
    }

    private StoryBuilder downloadStory() {
        String url = String.format(Locale.US, GOOGLE_XLSX_URL, docId);

        Request request = new Request.Builder()
                .url(url)
                .build();

        log.info("Reading story from " + url);

        try (Response response = Narrator.okHttpClient.newCall(request).execute()) {
            if (!response.isSuccessful())
                throw new ParseException("Error " + response.code() + " while downloading from " + url);
            if(!response.body().contentType().subtype().equalsIgnoreCase(GOOGLE_XLSX_SUBTYPE))
                throw new ParseException("Can't read blueprint. Have you enabled link sharing?");

            // Else successful, parse body
            SheetParser parser = new SheetParser();
            StoryBuilder builder = new StoryBuilder(storyService.bot);
            builder = parser.parseXLS(response.body().byteStream(), StoryBuilder.class, builder);

            log.error("received {}", builder);

            // Done
            return builder;
        } catch (Throwable e) {
            throw new RuntimeException("Failed to read story from " + url, e);
        }
    }


    @Override
    public long onServiceStart(Narrator bot) {
        if(loadTask != null)
            throw new IllegalStateException("already started");     // UB

        // Send acknowledgement message to sender
        statusMessage = storyService.config.uploadAcknowledgeMessage.select().build(storyService.bot, channel,
                "sender", user.getAsMention()
        ).complete();

        // Load dialogue tree
        loadTask = bot.executor.submit(this::downloadStory);

        return LOAD_CHECK_INTERVAL;
    }

    @Override
    public long processService(Narrator bot) {
        // Still loading check if finished
        if(!loadTask.isDone())
            return LOAD_CHECK_INTERVAL;       // try again
        // Get result
        try {
            StoryBuilder builder = loadTask.get();

            // Save story
            StoryService.StoryInfo storyInfo = storyService.saveStory(builder, user.getId(), docId);

            // Inform success
            bot.queue(() -> storyService.config.uploadSuccessMessage.select().edit(
                    bot,
                    statusMessage,
                    "sender", user.getAsMention(),
                    "code", storyInfo.id
            ), log, "Sending upload success message");

        } catch (Throwable e) {
            log.error("Failed build story", e);

            // Here we prettify the error message
            while (e != null && !(e instanceof ParseException))
                e = e.getCause();
            String message;
            if (e != null)
                message = e.getMessage();
            else
                message = storyService.config.uploadUnknownError.select();

            // Update status
            String finalMessage = message;
            bot.queue(() -> storyService.config.uploadErrorMessage.select().edit(bot, statusMessage,
                    "sender", user.getAsMention(),
                    "error", finalMessage
            ), log, "Update status message to inform failure");
        }

        // Done, stop service
        bot.removeService(this);

        return -1;
    }

}
