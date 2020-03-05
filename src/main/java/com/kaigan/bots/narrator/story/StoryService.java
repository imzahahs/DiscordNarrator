package com.kaigan.bots.narrator.story;

import com.kaigan.bots.narrator.Narrator;
import com.kaigan.bots.narrator.NarratorService;
import com.kaigan.bots.narrator.ProcessedMessage;
import com.kaigan.bots.narrator.SheetMessageBuilder;
import net.dv8tion.jda.api.events.message.guild.GuildMessageReceivedEvent;
import net.dv8tion.jda.internal.utils.Checks;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import sengine.calc.SetRandomizedSelector;
import sengine.mass.Mass;
import sengine.mass.MassSerializable;
import sengine.mass.io.Input;
import sengine.mass.io.Output;
import sengine.sheets.OnSheetEnded;
import sengine.sheets.SheetFields;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class StoryService implements NarratorService {
    private static final Logger log = LogManager.getLogger("StoryService");

    private static final String GOOGLE_SHEET_URL = "https://docs.google.com/spreadsheets/d/";

    private static final Pattern whitespaceMatcher = Pattern.compile("\\s+");

    private static final String SAVE_STORIES_LIST = "StoryService.stories";

    private static final String STORY_EXTENSION = ".story";

    @SheetFields(requiredFields = {
            "storiesPath",
            "codeGenerateMin", "codeGenerateMax",
            "names", "monitoredChannels",
            "uploadAcknowledgeMessage", "uploadUnknownError", "uploadErrorMessage", "uploadSuccessMessage",
            "storyNotFoundMessage",
            "intro"
    })
    public static class Config implements OnSheetEnded {

        @SheetFields(requiredFields = { "title", "description", "player", "status" })
        public static class IntroMessageConfig {

            @SheetFields(requiredFields = { "title", "waiting" })
            public static class PlayerRowConfig {
                public String title;
                public String waiting;
            }

            @SheetFields(requiredFields = { "title", "preparing", "notEnoughResources", "starting",
                    "waitingColor", "preparingColor", "notEnoughResourcesColor", "startingColor"
            })
            public static class StatusConfig {
                public String title;
                public String preparing;
                public String notEnoughResources;
                public String starting;

                public int waitingColor = 11991952;
                public int preparingColor = 11991952;
                public int notEnoughResourcesColor = 11991952;
                public int startingColor = 11991952;

                public void waitingColor(String color) { this.waitingColor = (int) Long.parseLong(color, 16); }
                public void preparingColor(String color) { this.preparingColor = (int) Long.parseLong(color, 16); }
                public void notEnoughResourcesColor(String color) { this.notEnoughResourcesColor = (int) Long.parseLong(color, 16); }
                public void startingColor(String color) { this.startingColor = (int) Long.parseLong(color, 16); }

            }

            public String title;
            public String description;

            public PlayerRowConfig player;
            public StatusConfig status;


        }

        public String storiesPath;
        public long codeGenerateMin = 1000;
        public long codeGenerateMax = 9999;

        public String[] names;
        public String[] monitoredChannels;

        public SetRandomizedSelector<SheetMessageBuilder> uploadAcknowledgeMessage;
        public SetRandomizedSelector<String> uploadUnknownError;
        public SetRandomizedSelector<SheetMessageBuilder> uploadErrorMessage;
        public SetRandomizedSelector<SheetMessageBuilder> uploadSuccessMessage;

        public SetRandomizedSelector<SheetMessageBuilder> storyNotFoundMessage;

        public IntroMessageConfig intro;


        public void uploadAcknowledgeMessage(SheetMessageBuilder[] array) { uploadAcknowledgeMessage = new SetRandomizedSelector<>(array); }
        public void uploadUnknownError(String[] array) { uploadUnknownError = new SetRandomizedSelector<>(array); }
        public void uploadErrorMessage(SheetMessageBuilder[] array) { uploadErrorMessage = new SetRandomizedSelector<>(array); }
        public void uploadSuccessMessage(SheetMessageBuilder[] array) { uploadSuccessMessage = new SetRandomizedSelector<>(array); }

        public void storyNotFoundMessage(SheetMessageBuilder[] array) { storyNotFoundMessage = new SetRandomizedSelector<>(array); }

        @Override
        public void onSheetEnded() {
            Checks.positive(codeGenerateMin, "codeGenerateMin");
            Checks.positive(codeGenerateMax, "codeGenerateMax");
            if(codeGenerateMin >= codeGenerateMax)
                throw new IllegalArgumentException("codeGenerateMin must be < codeGenerateMax");
        }
    }

    public static class StoryInfo implements MassSerializable {
        public final String id;
        public final String owner;
        public final String docId;
        public final long time;

        public StoryInfo(String id, String owner, String docId) {
            this.id = id;
            this.owner = owner;
            this.docId = docId;

            time = System.currentTimeMillis();
        }

        @MassConstructor
        public StoryInfo(String id, String owner, String docId, long time) {
            this.id = id;
            this.owner = owner;
            this.docId = docId;
            this.time = time;
        }

        @Override
        public Object[] mass() {
            return new Object[] { id, owner, docId, time};
        }
    }

    final Narrator bot;
    Config config;

    private Set<String> monitoredChannels;
    private Set<String> names;

    private final Map<String, StoryInfo> storyCodeLookup = new HashMap<>();
    private final Map<String, StoryInfo> storyDocLookup = new HashMap<>();

    public StoryInfo findStory(String id) {
        return storyCodeLookup.get(id);
    }

    public StoryBuilder loadStory(StoryInfo storyInfo) {
        Path path = Paths.get(config.storiesPath, storyInfo.id + STORY_EXTENSION);
        try(FileInputStream storyFile = new FileInputStream(path.toString())) {
            Mass mass = new Mass();
            mass.load(new Input(storyFile), STORY_EXTENSION, bot.builder.key);
            return mass.get(0, true);
        } catch (Throwable e) {
            throw new RuntimeException("Unable to load story: " + storyInfo.id, e);
        }
    }

    public StoryInfo saveStory(StoryBuilder builder, String owner, String docId) {
        // Check if story already added
        StoryInfo storyInfo = storyDocLookup.get(docId);
        if(storyInfo == null) {
            // Generate new id

            // Make sure we didnt run out of codes
            long maxAvailable = config.codeGenerateMax - config.codeGenerateMin;
            if(maxAvailable <= storyCodeLookup.size())
                throw new IllegalStateException("number of stories exceed code generation range");

            // Generate unique id
            String id;
            do {
                id = Long.toString(ThreadLocalRandom.current().nextLong(config.codeGenerateMin, config.codeGenerateMax + 1));
            } while(storyCodeLookup.containsKey(id));

            // Write new entry
            storyInfo = new StoryInfo(id, owner, docId);

            log.info("Saving new story {} from document {}", id, docId);
        }
        else {
            // Recreate entry
            storyInfo = new StoryInfo(storyInfo.id, owner, docId);

            log.info("Overwriting existing story {} from document {}", storyInfo.id, docId);
        }

        // Save builder
        Mass mass = new Mass();
        mass.add(builder);

        Path path = Paths.get(config.storiesPath, storyInfo.id + STORY_EXTENSION);
        try {
            // Mkdirs
            if (path.getParent() != null)
                Files.createDirectories(path.getParent());
            try (FileOutputStream storyFile = new FileOutputStream(path.toString(), false)) {
                mass.save(new Output(storyFile), STORY_EXTENSION, bot.builder.key);
            }
        } catch (Throwable e) {
            throw new RuntimeException("Unable to save story: " + storyInfo.id, e);
        }

        // Save entry
        storyCodeLookup.put(storyInfo.id, storyInfo);
        storyDocLookup.put(storyInfo.docId, storyInfo);

        // Save all entries
        bot.putSave(SAVE_STORIES_LIST, new ArrayList<>(storyCodeLookup.values()));

        return storyInfo;
    }



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

        // Load save
        List<StoryInfo> list = bot.getSave(SAVE_STORIES_LIST);
        if(list != null) {
            list.forEach(info -> {
                storyCodeLookup.put(info.id, info);
                storyDocLookup.put(info.docId, info);
            });
        }
    }


    @Override
    public boolean processMessage(Narrator bot, GuildMessageReceivedEvent event, ProcessedMessage message) {
        String[] words = whitespaceMatcher.matcher(message.raw).replaceAll(" ").split(" ");

        // Only process monitored channels
        if(!monitoredChannels.contains(event.getChannel().getId()))
            return false;       // not monitored

        // Check if mentioned
        boolean hasMentioned = Arrays.stream(words).anyMatch(word -> names.contains(word.toLowerCase()));

        if(!hasMentioned)
            return false;       // not monitored

        // Check if sent google sheet url
        String googleSheetUrl = Arrays.stream(words)
                .filter(word -> word.startsWith(GOOGLE_SHEET_URL))
                .findFirst().orElse(null);
        if(googleSheetUrl != null) {
            String id = googleSheetUrl.substring(GOOGLE_SHEET_URL.length()).split("/")[0];

            log.info("Received upload request: " + id);

            StoryBuilderService builderService = new StoryBuilderService(this, event, id);
            bot.addService(builderService);

            return false;
        }

        // Check if sent story id
        String storyId = Arrays.stream(words)
                .filter(StringUtils::isNumeric)
                .findFirst().orElse(null);
        if(storyId != null) {
            log.info("Received story request: " + storyId);

            StoryInstanceService instanceService = new StoryInstanceService(this, event, storyId);
            bot.addService(instanceService);

            return false;
        }

        return false;
    }
}
