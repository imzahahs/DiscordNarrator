package com.kaigan.bots.narrator;

import com.kaigan.bots.narrator.story.StoryService;
import net.dv8tion.jda.internal.utils.Checks;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import sengine.sheets.OnSheetEnded;
import sengine.sheets.ParseException;
import sengine.sheets.SheetFields;
import sengine.sheets.SheetParser;

import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.URL;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@SheetFields(fields = {
        "key", "saveFilePath", "saveFileInterval",
        "downloadPath", "downloadMaxSize"
})
public class NarratorBuilder implements OnSheetEnded, NarratorProvider {
    private static final Logger log = LogManager.getLogger("NarratorBuilder");

    // Helper function to parse human readable durations
    private static final Pattern durationPattern = Pattern.compile("([0-9.]+)([a-z]+)");

    public static long parseDuration(String duration) {
        duration = duration.replaceAll("\\s","").toLowerCase(Locale.ENGLISH);
        Matcher matcher = durationPattern.matcher(duration);
        Instant instant = Instant.EPOCH;
        while (matcher.find()) {
            double num = Double.parseDouble(matcher.group(1));
            String type = matcher.group(2);

            switch (type) {
                case "ms":
                case "millisecond":
                case "milliseconds":
                    instant = instant.plusMillis(Math.round(num));
                    break;

                case "s":
                case "sec":
                case "secs":
                case "second":
                case "seconds":
                    instant = instant.plusMillis(Math.round(num * 1000));
                    break;

                case "m":
                case "min":
                case "mins":
                case "minute":
                case "minutes":
                    instant = instant.plusMillis(Math.round(num * 1000 * 60));
                    break;

                case "h":
                case "hr":
                case "hrs":
                case "hour":
                case "hours":
                    instant = instant.plusMillis(Math.round(num * 1000 * 60 * 60));
                    break;

                case "d":
                case "day":
                case "days":
                    instant = instant.plusMillis(Math.round(num * 1000 * 60 * 60 * 24));
                    break;

                case "w":
                case "week":
                case "weeks":
                    instant = instant.plusMillis(Math.round(num * 1000 * 60 * 60 * 24 * 7));
                    break;

                case "mo":
                case "month":
                case "months":
                    instant = instant.plusMillis(Math.round(num * 1000 * 60 * 60 * 24 * 30.42));
                    break;

                case "y":
                case "year":
                case "years":
                    instant = instant.plusMillis(Math.round(num * 1000 * 60 * 60 * 24 * 30.42 * 12));
                    break;

                default:
                    throw new ParseException("Unknown duration: " + type);
            }
        }
        return instant.toEpochMilli();
    }

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

    public String key;

    public String saveFilePath;
    public long saveFileInterval;

    public String downloadPath;
    public long downloadMaxSize;


    // Narrator thread only

    public void prepareSave(String path, long interval) {
        this.saveFilePath = path;
        this.saveFileInterval = interval;
        bot.reloadSave();
    }

    public void configureStoryService(StoryService.Config config) {
        StoryService service = bot.getService(StoryService.class);
        if(service != null)
            service.setConfig(config);
        else
            bot.addService(new StoryService(bot, config));
    }

    @Override
    public void onSheetEnded() {
        Checks.notNull(key, "key");
        Checks.notNull(saveFilePath, "saveFilePath");
        Checks.positive(saveFileInterval, "saveFileInterval");
        Checks.notNull(downloadPath, "downloadPath");
        Checks.positive(downloadMaxSize, "downloadMaxSize");
    }

    @Override
    public Narrator getBot() {
        return bot;
    }
}
