package com.kaigan.bots.narrator.story;

import com.kaigan.bots.narrator.*;
import net.dv8tion.jda.api.entities.Category;
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
            "storyBots", "storyBotTimeout", "storyBotTypingInterval", "storyChannelTimestep", "storyReplySelectionDelay",
            "storyCategory",
            "uploadAcknowledgeMessage", "uploadUnknownError", "uploadErrorMessage", "uploadSuccessMessage",
            "storyNotFoundMessage",
            "intro", "introInviteTimeout", "introConcludedTimeout", "instanceTimeout",
            "instanceSpeedCommand",
            "instanceQuitCommand", "instanceQuitMessage", "instanceQuitDelay",
            "chooseReplyMessage", "chooseReplyRowFormat"
    })
    public static class Config implements OnSheetEnded {

        @SheetFields(requiredFields = { "role", "token" })
        public static class StoryBotConfig {
            public String role;
            public String token;
        }

        @SheetFields(requiredFields = { "title", "description", "playerWaiting", "playerJoined", "status" })
        public static class IntroMessageConfig {
            @SheetFields(requiredFields = { "preparing", "notEnoughResources", "starting",
                    "waitingColor", "preparingColor", "notEnoughResourcesColor", "startingColor"
            })
            public static class StatusConfig {
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

            public String playerWaiting;
            public String playerJoined;

            public StatusConfig status;
        }


        public String storiesPath;
        public long codeGenerateMin = 1000;
        public long codeGenerateMax = 9999;

        public String[] names;
        public String[] monitoredChannels;

        public StoryBotConfig[] storyBots;
        public long storyBotTimeout;
        public long storyBotTypingInterval;
        public long storyChannelTimestep;
        public long storyReplySelectionDelay;

        public String storyCategory;

        public SetRandomizedSelector<SheetMessageBuilder> uploadAcknowledgeMessage;
        public SetRandomizedSelector<String> uploadUnknownError;
        public SetRandomizedSelector<SheetMessageBuilder> uploadErrorMessage;
        public SetRandomizedSelector<String> uploadSuccessMessage;

        public SetRandomizedSelector<SheetMessageBuilder> storyNotFoundMessage;

        public IntroMessageConfig intro;
        public long introInviteTimeout;
        public long introConcludedTimeout;
        public long instanceTimeout;

        public String instanceSpeedCommand;

        public String instanceQuitCommand;
        public SetRandomizedSelector<SheetMessageBuilder> instanceQuitMessage;
        public long instanceQuitDelay;

        public SheetMessageBuilder chooseReplyMessage;
        public String chooseReplyRowFormat;


        public void uploadAcknowledgeMessage(SheetMessageBuilder[] array) { uploadAcknowledgeMessage = new SetRandomizedSelector<>(array); }
        public void uploadUnknownError(String[] array) { uploadUnknownError = new SetRandomizedSelector<>(array); }
        public void uploadErrorMessage(SheetMessageBuilder[] array) { uploadErrorMessage = new SetRandomizedSelector<>(array); }
        public void uploadSuccessMessage(String[] array) { uploadSuccessMessage = new SetRandomizedSelector<>(array); }

        public void storyNotFoundMessage(SheetMessageBuilder[] array) { storyNotFoundMessage = new SetRandomizedSelector<>(array); }

        public void storyBotTimeout(String duration) { storyBotTimeout = NarratorBuilder.parseDuration(duration); }
        public void storyBotTypingInterval(String duration) { storyBotTypingInterval = NarratorBuilder.parseDuration(duration); }
        public void storyChannelTimestep(String duration) { storyChannelTimestep = NarratorBuilder.parseDuration(duration); }
        public void storyReplySelectionDelay(String duration) { storyReplySelectionDelay = NarratorBuilder.parseDuration(duration); }

        public void introInviteTimeout(String duration) { introInviteTimeout = NarratorBuilder.parseDuration(duration); }
        public void introConcludedTimeout(String duration) { introConcludedTimeout = NarratorBuilder.parseDuration(duration); }
        public void instanceTimeout(String duration) { instanceTimeout = NarratorBuilder.parseDuration(duration); }

        public void instanceQuitMessage(SheetMessageBuilder[] array) { instanceQuitMessage = new SetRandomizedSelector<>(array); }
        public void instanceQuitDelay(String duration) { instanceQuitDelay = NarratorBuilder.parseDuration(duration); }


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

    private List<StoryBot> storyBots;

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

    Optional<StoryBot> requestBot(StoryInstanceService instance, StoryBuilder.NpcBuilder state) {
        return storyBots.stream()
                // Check if there is an existing bot already in such state
                .sorted(Comparator.comparing(StoryBot::isOnline).reversed())
                .filter(bot -> bot.acquire(instance, state, false))
                .findFirst()
                // Else need to reconfigure existing bot
                .or(() -> storyBots.stream()
                        .filter(StoryBot::isFree)
                        .min((bot1, bot2) -> {
                            // First sort by profile compatibility
                            boolean bot1ProfileCompatible = state.profilePic.equals(bot1.getProfilePic());
                            boolean bot2ProfileCompatible = state.profilePic.equals(bot2.getProfilePic());
                            if(bot1ProfileCompatible == bot2ProfileCompatible) {
                                // Then sort by online status
                                if(bot1.isOnline() == bot2.isOnline())
                                    return Long.compare(bot2.getProfilePicAge(), bot1.getProfilePicAge());      // Then sort by profile pic age, to choose the oldest
                                return Boolean.compare(bot2.isOnline(), bot1.isOnline());
                            }
                            return Boolean.compare(bot2ProfileCompatible, bot1ProfileCompatible);
                        })
                        .filter(bot -> bot.acquire(instance, state, true)));
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

        storyBots = Arrays.stream(config.storyBots)
                .map(botConfig -> new StoryBot(this, botConfig.token, botConfig.role))
                .collect(Collectors.toList());

    }

    public StoryService(Narrator bot, Config config) {
        this.bot = bot;

        setConfig(config);

        // Load save
        List<StoryInfo> list = bot.<List<StoryInfo>>getSave(SAVE_STORIES_LIST).orElse(Collections.emptyList());
        list.forEach(info -> {
            storyCodeLookup.put(info.id, info);
            storyDocLookup.put(info.docId, info);
        });
    }

    @Override
    public long onServiceStart(Narrator bot) {
        // Get story Category
        Category storyCategory = bot.guild.getCategoriesByName(config.storyCategory, false).get(0);

        // Clear all story channels from previous session
        bot.guild.getTextChannels().stream()
                .filter(channel -> channel.getParent() == storyCategory)
                .forEach(channel -> bot.queue(channel::delete, log, "Delete previous session channels"));

        return -1;
    }

    @Override
    public boolean processMessage(Narrator bot, GuildMessageReceivedEvent event, ProcessedMessage message) {
        // Only process monitored channels
        if(!monitoredChannels.contains(event.getChannel().getId()))
            return false;       // not monitored

        Optional<String[]> commands = parseCommands(message.raw);
        if(!commands.isPresent())
            return false;       // not commands

        String[] parameters = commands.get();

        if(parameters.length == 1 && parameters[0].startsWith(GOOGLE_SHEET_URL)) {
            String id = parameters[0].substring(GOOGLE_SHEET_URL.length()).split("/")[0];

            log.info("Received upload request: " + id);

            StoryBuilderService builderService = new StoryBuilderService(this, event, id);
            bot.addService(builderService);

            return false;
        }

        // Check if sent story id
        if(parameters.length == 1 && StringUtils.isNumeric(parameters[0])) {

            log.info("Received story request: " + parameters[0]);

            StoryInstanceService instanceService = new StoryInstanceService(
                    this,
                    event.getChannel(),
                    event.getMember(),
                    null,
                    parameters[0]
            );
            bot.addService(instanceService);

            return false;
        }

        return false;
    }

    Optional<String[]> parseCommands(String message) {
        String[] words = whitespaceMatcher.matcher(message).replaceAll(" ").split(" ");
        if(words.length < 2)
            return Optional.empty();     // empty, prevent UB
        if(!names.contains(words[0].toLowerCase()))
            return Optional.empty();     // not referencing service, ignore
        return Optional.of(Arrays.copyOfRange(words, 1, words.length));
    }
}
