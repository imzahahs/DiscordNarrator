package com.kaigan.bots.narrator.story;

import com.badlogic.gdx.utils.Array;
import com.kaigan.bots.narrator.script.Operation;
import com.kaigan.bots.narrator.script.ScriptBuilder;
import sengine.calc.Range;
import sengine.sheets.OnSheetEnded;
import sengine.sheets.ParseException;
import sengine.sheets.SheetFields;
import sengine.sheets.SheetParser;

import java.text.DateFormatSymbols;
import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.TimeZone;


@SheetFields(requiredFields = { "name", "topic" })
public class StoryChannelBuilder {

    public static final String ORIGIN_NPC = "npc";
    public static final String ORIGIN_PLAYER = "player";

    private static final String defaultEmptyString = "";
    private static final UserMessageModel[] defaultEmptyUserMessages = new UserMessageModel[0];
    private static final SenderMessageModel[] defaultEmptySenderMessages = new SenderMessageModel[0];

    @SheetFields(fields = { "message", "tags", "is_hidden" })
    public static class UserMessageModel {
        public String message = defaultEmptyString;
        public String tags = null;
        public boolean is_hidden = false;
    }

    @SheetFields(fields = { "message", "origin", "idle_time", "typing_time", "onSeen" })
    public static class SenderMessageModel {
        public String message = defaultEmptyString;
        public String origin = ORIGIN_NPC;

        public float idle_time = 0;
        public float typing_time = 0;

        public Operation[] onSeen;

        public void onSeen(ScriptBuilder builder) {
            onSeen = builder.build();
        }
    }

    @SheetFields(fields = { "tags", "user_messages", "is_user_ignored", "sender_messages", "tags_to_unlock", "tags_to_lock", "trigger" })
    public static class ConversationModel {


        public String tags = defaultEmptyString;

        public UserMessageModel[] user_messages = defaultEmptyUserMessages;

        public boolean is_user_ignored = false;

        public SenderMessageModel[] sender_messages = defaultEmptySenderMessages;

        public String tags_to_unlock = defaultEmptyString;
        public String tags_to_lock = defaultEmptyString;

        public Operation[] trigger;

        public void trigger(ScriptBuilder builder) {
            trigger = builder.build();
        }
    }

    public String name;
    public String topic;

    public Array<ConversationModel> conversations = new Array<>(ConversationModel.class);


    public StoryChannelBuilder() {
        ConversationBuilder.linkCounter = 0;                // Reset links for each dialogue tree

        // Reset timers
        ConversationBuilder.switchTime = ConversationBuilder.defaultSwitchTime;
        ConversationBuilder.sentenceTime = ConversationBuilder.defaultSentenceTime;
        ConversationBuilder.wordsPerMinute = ConversationBuilder.defaultWordsPerMinute;
    }


    @SheetFields(fields = { "tags" })
    public static class ConversationBuilder implements OnSheetEnded {

        private static String appendString(String source, String string) {
            if(source == null || source.isEmpty())
                return string;
            return source + ", " + string;
        }

        public static int linkCounter = 0;
        public static String linkPrefix = "__LINK";

        public static SimpleDateFormat parseFormat = new SimpleDateFormat("dd MMMM yyyy h:mm a", Locale.US);
        public static SimpleDateFormat dateFormat = new SimpleDateFormat("dd MMMM yyyy", Locale.US);
        public static SimpleDateFormat timeFormat = new SimpleDateFormat("h:mm a",Locale.US);
        public static TimeZone timeZone = TimeZone.getTimeZone("GMT+8:00");

        public static float pastTimeMultiplier = 10f;

        public static Range defaultSwitchTime = new Range(0.3f, 0.3f);
        public static Range defaultSentenceTime = new Range(0.9f, 0.8f);                 // 0.9f, 0.8f
        public static float defaultWordsPerMinute = 6f;                 // 12f

        private static Range switchTime = defaultSwitchTime;
        private static Range sentenceTime = defaultSentenceTime;
        private static float wordsPerMinute = defaultWordsPerMinute;

        // Initialize statics
        static {
            parseFormat.setTimeZone(timeZone);
            dateFormat.setTimeZone(timeZone);
            timeFormat.setTimeZone(timeZone);
            DateFormatSymbols lowercaseAMPM = new DateFormatSymbols(Locale.US);
            lowercaseAMPM.setAmPmStrings(new String[] { "am", "pm" });
            timeFormat.setDateFormatSymbols(lowercaseAMPM);
        }


        public String tags = null;

        private boolean isRepeatable = false;

        // Current
        // Conversations
        private final Array<ConversationModel> conversations = new Array<ConversationModel>(ConversationModel.class);
        private ConversationModel current = null;
        private boolean isTagsModified = false;
        private String currentSender = "";

        private final Array<ConversationBuilder> splits = new Array<ConversationBuilder>(ConversationBuilder.class);
        private final Array<String> splitTags = new Array<String>(String.class);


        // Time
        private float queuedIdleTime = 0;
        private float queuedTypingTime = 0;

        public void repeatable() {
            isRepeatable  = true;
        }

        public void split_add(String list, ConversationBuilder builder) {
            if(builder == null || builder.conversations.size == 0)
                throw new ParseException("Missing added conversation");
            // Get a unique start and end name for this section
            String sectionOpenName = linkPrefix + linkCounter;
            linkCounter++;
            String sectionFinishedName = linkPrefix + linkCounter;
            linkCounter++;
            // Add open and finish links to this section
            ConversationModel firstConversation = builder.conversations.first();
            firstConversation.tags = appendString(firstConversation.tags, sectionOpenName);
            firstConversation.tags_to_lock = appendString(firstConversation.tags_to_lock, sectionOpenName);
            ConversationModel lastConversation = builder.conversations.peek();
            lastConversation.tags_to_unlock = appendString(lastConversation.tags_to_unlock, sectionFinishedName);
            // Add section
            conversations.addAll(builder.conversations);

            // Now link to specified splits
            String[] indices = SheetParser.splitStringCSV(list);
            for(int c = 0; c < indices.length; c++) {
                int index = Integer.valueOf(indices[c]) - 1;
                // Create new conversation to the last one
                ConversationBuilder split = splits.items[index];
                split.unlocks(sectionOpenName);
                split.push();
                ConversationModel conversation = split.conversations.peek();
                conversation.tags = appendString(conversation.tags, sectionFinishedName);
                conversation.tags_to_lock = appendString(conversation.tags_to_lock, sectionFinishedName);
            }
        }

        public void split(ConversationBuilder builder) {
            if(builder == null)
                builder = new ConversationBuilder();
            if(builder.conversations.size == 0)
                builder.push();
            // Get a unique name for this split
            String splitName = linkPrefix + linkCounter;
            linkCounter++;
            splitTags.add(splitName);
            // Unlock this split
            unlocks(splitName);
            // Label this split
            ConversationModel firstConversation = builder.conversations.first();
            firstConversation.tags = appendString(firstConversation.tags, splitName);
            // Add this split
            splits.add(builder);
        }

        /**
         * Waits for all of the previous splits to end
         */
        public void join_all() {
            if(splits.size == 0)
                throw new ParseException("Mothing to join");

            // Create new tag for the join
            String joinName = linkPrefix + linkCounter;
            linkCounter++;

            unlocks(joinName);          // unlock the join automatically, but it will only unlock on IDLE

            // Compile all split tags into one
            String allTags = null;
            for(String tag : splitTags)
                allTags = appendString(allTags, tag);
            allTags = appendString(allTags, joinName);          // also include the join name

            // For all splits, keep track of another set of links so that each split can only be entered once
            for(ConversationBuilder split : splits) {
                // Create a new tag
                String lockName = linkPrefix + linkCounter;
                linkCounter++;

                // Label this split with the absence of the new tag, so that this conversation does not recurse
                conversations.addAll(split.conversations);
                ConversationModel conversation = split.conversations.first();
                conversation.tags = appendString(conversation.tags, "!" + lockName);

                // Lock all other splits (and the join) while this conversation is going on
                conversation.tags_to_lock = appendString(conversation.tags_to_lock, allTags);

                // In the last conversation, unlock all back, including the lock tag so that this split cannot be entered again
                conversation = split.conversations.peek();
                conversation.tags_to_unlock = appendString(conversation.tags_to_unlock, allTags);
                conversation.tags_to_unlock = appendString(conversation.tags_to_unlock, lockName);
            }

            // Clear
            splits.clear();
            splitTags.clear();

            // Create join
            current = new ConversationModel();
            current.tags = appendString(joinName, "IDLE");
            current.tags_to_lock = joinName;
            conversations.add(current);
        }

        /**
         * Waits for either one of the previous split to end, persist choices
         */
        public void join_persist() {
            if(splits.size == 0)
                throw new ParseException("Mothing to join");

            // Get a unique name for this join
            String joinName = linkPrefix + linkCounter;
            linkCounter++;

            // Compile all split tags into one
            String allTags = null;
            for(String tag : splitTags)
                allTags = appendString(allTags, tag);

            // For all splits, automatically unlock this join if either of the splits were unlocked
            for(ConversationBuilder split : splits) {
                // Create a new tag
                String lockName = linkPrefix + linkCounter;
                linkCounter++;
                // In the first conversation, lock all other splits
                conversations.addAll(split.conversations);
                ConversationModel conversation = split.conversations.first();
                conversation.tags = appendString(conversation.tags, "!" + lockName);
                conversation.tags_to_lock = appendString(conversation.tags_to_lock, allTags);
                // In the last conversation, unlock all, join tag and also this conversation's lock tag
                conversation = split.conversations.peek();
                conversation.tags_to_unlock = appendString(conversation.tags_to_unlock, joinName);
                conversation.tags_to_unlock = appendString(conversation.tags_to_unlock, lockName);
            }

            // Clear
            splits.clear();
            splitTags.clear();

            // Create join
            current = new ConversationModel();
            current.tags = joinName;
            current.tags_to_lock = joinName;
            conversations.add(current);
        }

        /**
         * Waits for either one of the previous split to end
         */
        public void join() {
            if(splits.size == 0)
                throw new ParseException("Mothing to join");

            // Get a unique name for this join
            String joinName = linkPrefix + linkCounter;
            linkCounter++;

            // Compile all split tags into one
            String allTags = null;
            for(String tag : splitTags)
                allTags = appendString(allTags, tag);

            // For all splits, automatically unlock this join if either of the splits were unlocked
            for(ConversationBuilder split : splits) {
                // In the first conversation, lock all other splits
                conversations.addAll(split.conversations);
                ConversationModel conversation = split.conversations.first();
                conversation.tags_to_lock = appendString(conversation.tags_to_lock, allTags);
                // In the last conversation, unlock join tag
                conversation = split.conversations.peek();
                conversation.tags_to_unlock = appendString(conversation.tags_to_unlock, joinName);
            }

            // Clear
            splits.clear();
            splitTags.clear();

            // Create join
            current = new ConversationModel();
            current.tags = joinName;
            current.tags_to_lock = joinName;
            conversations.add(current);
        }


        // Timing
        public void conversation_speed(float minTime, float randomTime) {
            ConversationBuilder.switchTime = new Range(minTime, randomTime);
        }

        public void typing_speed(float minTime, float randomTime, float wordsPerMinute) {
            ConversationBuilder.sentenceTime = new Range(minTime, randomTime);
            ConversationBuilder.wordsPerMinute = wordsPerMinute;
        }

        public void reset_conversation_speed() {
            ConversationBuilder.switchTime = ConversationBuilder.defaultSwitchTime;
        }

        public void reset_typing_speed() {
            ConversationBuilder.sentenceTime = ConversationBuilder.defaultSentenceTime;
            ConversationBuilder.wordsPerMinute = ConversationBuilder.defaultWordsPerMinute;
        }

        public void wait(float seconds) {
            validatePreviousSplits();
            queuedIdleTime += seconds;
        }

        public void wait_typing(float seconds) {
            validatePreviousSplits();
            queuedTypingTime += seconds;
        }


        private void push() {
            if(current == null) {
                current = new ConversationModel();
                current.tags = tags;
                conversations.add(current);

                if(!isRepeatable) {
                    String nextLinkName = linkPrefix + linkCounter;
                    linkCounter++;

                    current.tags = appendString(current.tags, "!" + nextLinkName);
                    current.tags_to_unlock = nextLinkName;
                }
            }
            else if(conversations.size == 1) {
                // Create a new link to close up first conversation
//                String linkName = linkPrefix + linkCounter;
//                linkCounter++;
//                current.tags = appendString(current.tags, "!" + linkName);
                // Create a new link for second conversation
                String nextLinkName = linkPrefix + linkCounter;
                linkCounter++;
//                current.tags_to_unlock = appendString(current.tags_to_unlock, linkName + ", " + nextLinkName);
                current.tags_to_unlock = appendString(current.tags_to_unlock, nextLinkName);

                current = new ConversationModel();
                current.tags = nextLinkName;
                current.tags_to_lock = nextLinkName;
                conversations.add(current);
            }
            else {
                // Create a new link for next conversation
                String nextLinkName = linkPrefix + linkCounter;
                linkCounter++;

                current.tags_to_unlock = appendString(current.tags_to_unlock, nextLinkName);

                current = new ConversationModel();
                current.tags = nextLinkName;
                current.tags_to_lock = nextLinkName;
                conversations.add(current);
            }
            // Indicate tags has not been modified yet
            isTagsModified = false;
        }

        public void choice(UserMessageModel[] messages) {
            validatePreviousSplits();
            // Choices will always start a new conversation
            if(current == null || current.user_messages != defaultEmptyUserMessages  || current.sender_messages != defaultEmptySenderMessages)
                push();
            current.user_messages = messages;
            currentSender = "user";
            // Cleanup messages
            for(UserMessageModel message : messages)
                message.message = message.message.trim();
        }

        public void reply(SenderMessageModel[] messages) {
            for(SenderMessageModel message : messages) {
                message.origin = "user";
            }
            response(messages);
        }

        public void response(SenderMessageModel[] messages) {
            validatePreviousSplits();
            // Update message times
            for(SenderMessageModel message : messages) {
                // Live
                if (!message.origin.equals("user") && message.idle_time == 0 && message.typing_time == 0) {     // live user responses have no timing
                    // Check if new switching person
                    if(!currentSender.equals(message.origin))
                        queuedIdleTime += switchTime.generate();
                    message.idle_time = queuedIdleTime + sentenceTime.generate();
                    queuedIdleTime = 0;

                    // Typing speed
                    int words = message.message.trim().split("\\s+").length;
                    message.typing_time = (1f / wordsPerMinute) * words;
                    message.typing_time += queuedTypingTime;
                    queuedTypingTime = 0;
                }

                currentSender = message.origin;
            }

            if(current == null || isTagsModified)
                push();         // first conversation or tags has been modified
            else if(current.sender_messages != null) {
                // Tags has not been modified and there are existing responses, just append
                SenderMessageModel[] array = new SenderMessageModel[current.sender_messages.length + messages.length];
                System.arraycopy(current.sender_messages, 0, array, 0, current.sender_messages.length);
                System.arraycopy(messages, 0, array, current.sender_messages.length, messages.length);
                current.sender_messages = array;
                return;
            }
            // Else just use this one
            current.sender_messages = messages;
        }

        public void ignore_choice() {
            validatePreviousSplits();
            if(current != null && current.user_messages != defaultEmptyUserMessages)
                current.is_user_ignored = true;
        }

        public void trigger(ScriptBuilder builder) {
            validatePreviousSplits();
            if(current == null)
                push();
            current.trigger = builder.build();
            push();
        }

        private void unlocks(String tags) {
            if(current == null)
                push();
            current.tags_to_unlock = appendString(current.tags_to_unlock, tags);
            isTagsModified = true;          // indicate that cannot append sender messages together
        }

        private void locks(String tags) {
            if(current == null)
                push();
            current.tags_to_lock = appendString(current.tags_to_lock, tags);
            isTagsModified = true;          // indicate that cannot append sender messages together
        }

        public void allow(String tags) {
            validatePreviousSplits();
            unlocks(tags);
        }

        public void stop(String tags) {
            validatePreviousSplits();
            locks(tags);
        }

        public void proceed_when(String tags) {
            validatePreviousSplits();
            push();
            current.tags = appendString(current.tags, tags);
        }


        @Override
        public void onSheetEnded() {
            validatePreviousSplits();
        }

        private void validatePreviousSplits() {
            if(splits.size > 0)
                throw new RuntimeException("Missing join, join_all or join_persist for previous splits");
        }
    }


    public void add(ConversationBuilder builder) {
        conversations.addAll(builder.conversations);
    }


}
