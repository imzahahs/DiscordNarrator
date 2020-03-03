package com.kaigan.bots.narrator;

import com.kaigan.bots.narrator.story.StoryService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import sengine.sheets.SheetParser;

import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.URL;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

public class NarratorBuilder implements NarratorProvider {
    private static final Logger log = LogManager.getLogger("NarratorBuilder");

    public final String sheetFilename;
    public final String googleDocId;
    public final String mainSheetName;

    public Narrator bot;

    public NarratorBuilder(String sheetFilename, String googleDocId, String mainSheetName) {
        this.sheetFilename = sheetFilename;
        this.googleDocId = googleDocId;
        this.mainSheetName = mainSheetName;
    }

    public void info(String s) { log.info(s); }
    public void warn(String s) { log.warn(s); }
    public void error(String s) { log.error(s); }

    public void insert(String ... sheets) {
        SheetParser parser = new SheetParser();
        for(String sheet : sheets) {
            String[] path = sheet.split(":", 2);
            try(BufferedInputStream s = new BufferedInputStream(new FileInputStream(path[0]))) {
                parser.parseXLS(s, path[1], NarratorBuilder.class, this);
            } catch (Throwable e) {
                throw new RuntimeException("Failed to insert sheet: " + sheet, e);
            }
        }
    }

    public void downloadSheet(String filename, String docId) {
        String url = String.format(Locale.US, "https://docs.google.com/spreadsheets/d/%s/export?format=xlsx", docId);
        downloadUrl(url, filename);
    }

    public void downloadUrl(String url, String filename) {
        try {
            log.info("Downloading " + url);
            FileOutputStream fileOutputStream = new FileOutputStream(filename);
            FileChannel fileChannel = fileOutputStream.getChannel();

            ReadableByteChannel readableByteChannel = Channels.newChannel(new URL(url).openStream());
            long transferred = fileChannel.transferFrom(readableByteChannel, 0, Long.MAX_VALUE);
            log.info("Downloaded {} bytes", transferred);
        } catch (Throwable e) {
            throw new RuntimeException("Failed to download url: " + url, e);
        }
    }

    public void build(String token, String serverName) {
        // Download config doc
        downloadSheet(sheetFilename, googleDocId);
        // Start discordia
        bot = new Narrator(this, token, serverName);
        // Start main sheet
        bot.scheduler.execute(() -> insert(sheetFilename + ":" + mainSheetName));
    }

    public void after(long millis, String[][] sheet) {
        bot.scheduler.schedule(() -> run(sheet), millis, TimeUnit.MILLISECONDS);
    }

    public void dispatch(String[][] sheet) {
        bot.scheduler.execute(() -> run(sheet));
    }

    private void run(String[][] sheet) {
        SheetParser parser = new SheetParser();
        for(int r = 0; r < sheet.length; r++)
            parser.addRow(r + 1, sheet[r]);
        parser.parse(NarratorBuilder.class, this);
    }

    // Narrator thread only
    public void configureStoryService(StoryService.Config config) {
        StoryService service = bot.getService(StoryService.class);
        if(service != null)
            service.setConfig(config);
        else
            bot.addService(new StoryService(bot, config));
    }

    @Override
    public Narrator getBot() {
        return bot;
    }
}
