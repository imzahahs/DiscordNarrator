package com.kaigan.bots.narrator.story;

import com.kaigan.bots.narrator.script.ScriptBuilder;
import sengine.calc.Range;
import sengine.sheets.OnSheetEnded;
import sengine.sheets.ParseException;
import sengine.sheets.SheetFields;
import sengine.sheets.SheetParser;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@SheetFields(requiredFields = { "name", "topic" })
public class StoryChannelBuilder {

    public static final String ORIGIN_NARRATOR = "narrator";

    public String name;
    public String topic;

    public final List<Conversation> conversations = new ArrayList<>();

    public StoryChannelBuilder() {
        ConversationBuilder.linkCounter = 0;                // Reset links for each dialogue tree

        // Reset timers
        ConversationBuilder.switchTime = ConversationBuilder.defaultSwitchTime;
        ConversationBuilder.sentenceTime = ConversationBuilder.defaultSentenceTime;
        ConversationBuilder.wordsPerMinute = ConversationBuilder.defaultWordsPerMinute;
    }


    @SheetFields(fields = { "tags" })
    public static class ConversationBuilder implements OnSheetEnded {

        public static int linkCounter = 0;
        public static String linkPrefix = "__LINK";
        public static String selectedPlayer;
        public static String selectedNpc;

        public static Range defaultSwitchTime = new Range(0.3f, 0.3f);
        public static Range defaultSentenceTime = new Range(0.9f, 0.8f);                 // 0.9f, 0.8f
        public static float defaultWordsPerMinute = 6f;                 // 12f

        private static Range switchTime = defaultSwitchTime;
        private static Range sentenceTime = defaultSentenceTime;
        private static float wordsPerMinute = defaultWordsPerMinute;

        public final List<String> tags = new ArrayList<>();

        private boolean isRepeatable = false;

        // Current
        // Conversations
        private final List<Conversation> conversations = new ArrayList<>();
        private Conversation current = null;
        private boolean isTagsModified = false;
        private String currentSender = "";

        private final List<ConversationBuilder> splits = new ArrayList<>();
        private final List<String> splitTags = new ArrayList<>();

        // Time
        private float queuedIdleTime = 0;
        private float queuedTypingTime = 0;

        public void tags(String csv) { tags.addAll(Arrays.asList(SheetParser.splitStringCSV(csv))); }

        public void repeatable() {
            isRepeatable  = true;
        }

        public void splitAdd(String list, ConversationBuilder builder) {
            if(builder == null || builder.conversations.isEmpty())
                throw new ParseException("Missing added conversation");
            // Get a unique start and end name for this section
            String sectionOpenName = linkPrefix + linkCounter;
            linkCounter++;
            String sectionFinishedName = linkPrefix + linkCounter;
            linkCounter++;
            // Add open and finish links to this section
            Conversation firstConversation = builder.conversations.get(0);
            firstConversation.tags.add(sectionOpenName);
            firstConversation.tagsToLock.add(sectionOpenName);
            Conversation lastConversation = builder.conversations.get(builder.conversations.size() - 1);
            lastConversation.tagsToUnlock.add(sectionFinishedName);
            // Add section
            conversations.addAll(builder.conversations);

            // Now link to specified splits
            String[] indices = SheetParser.splitStringCSV(list);
            for(String text : indices) {
                int index = Integer.parseInt(text) - 1;
                // Create new conversation to the last one
                ConversationBuilder split = splits.get(index);
                split.unlocks(sectionOpenName);
                split.push();
                Conversation conversation = split.conversations.get(split.conversations.size() - 1);
                conversation.tags.add(sectionFinishedName);
                conversation.tagsToLock.add(sectionFinishedName);
            }
        }

        public void split(ConversationBuilder builder) {
            if(builder == null)
                builder = new ConversationBuilder();
            if(builder.conversations.isEmpty())
                builder.push();
            // Get a unique name for this split
            String splitName = linkPrefix + linkCounter;
            linkCounter++;
            splitTags.add(splitName);
            // Unlock this split
            unlocks(splitName);
            // Label this split
            Conversation firstConversation = builder.conversations.get(0);
            firstConversation.tags.add(splitName);
            // Add this split
            splits.add(builder);
        }

        /**
         * Waits for all of the previous splits to end
         */
        public void joinAll() {
            if(splits.isEmpty())
                throw new ParseException("Nothing to join");

            // Create new tag for the join
            String joinName = linkPrefix + linkCounter;
            linkCounter++;

            unlocks(joinName);          // unlock the join automatically, but it will only unlock on IDLE

            // Compile all split tags into one
            List<String> allTags = new ArrayList<>(splitTags);
            allTags.add(joinName);          // also include the join name

            // For all splits, keep track of another set of links so that each split can only be entered once
            for(ConversationBuilder split : splits) {
                // Create a new tag
                String lockName = linkPrefix + linkCounter;
                linkCounter++;

                // Label this split with the absence of the new tag, so that this conversation does not recurse
                conversations.addAll(split.conversations);
                Conversation conversation = split.conversations.get(0);
                conversation.tags.add("!" + lockName);

                // Lock all other splits (and the join) while this conversation is going on
                conversation.tagsToLock.addAll(allTags);

                // In the last conversation, unlock all back, including the lock tag so that this split cannot be entered again
                conversation = split.conversations.get(split.conversations.size() - 1);
                conversation.tagsToUnlock.addAll(allTags);
                conversation.tagsToUnlock.add(lockName);
            }

            // Clear
            splits.clear();
            splitTags.clear();

            // Create join
            current = new Conversation();
            current.tags.add(joinName);
            current.tags.add("IDLE");
            current.tagsToLock.add(joinName);
            conversations.add(current);
        }

        /**
         * Waits for either one of the previous split to end, persist choices
         */
        public void joinPersist() {
            if(splits.isEmpty())
                throw new ParseException("Nothing to join");

            // Get a unique name for this join
            String joinName = linkPrefix + linkCounter;
            linkCounter++;

            // Compile all split tags into one
            List<String> allTags = new ArrayList<>(splitTags);

            // For all splits, automatically unlock this join if either of the splits were unlocked
            for(ConversationBuilder split : splits) {
                // Create a new tag
                String lockName = linkPrefix + linkCounter;
                linkCounter++;
                // In the first conversation, lock all other splits
                conversations.addAll(split.conversations);
                Conversation conversation = split.conversations.get(0);
                conversation.tags.add("!" + lockName);
                conversation.tagsToLock.addAll(allTags);
                // In the last conversation, unlock all, join tag and also this conversation's lock tag
                conversation = split.conversations.get(split.conversations.size() - 1);
                conversation.tagsToUnlock.add(joinName);
                conversation.tagsToUnlock.add(lockName);
            }

            // Clear
            splits.clear();
            splitTags.clear();

            // Create join
            current = new Conversation();
            current.tags.add(joinName);
            current.tagsToLock.add(joinName);
            conversations.add(current);
        }

        /**
         * Waits for either one of the previous split to end
         */
        public void join() {
            if(splits.isEmpty())
                throw new ParseException("Nothing to join");

            // Get a unique name for this join
            String joinName = linkPrefix + linkCounter;
            linkCounter++;

            // Compile all split tags into one
            List<String> allTags = new ArrayList<>(splitTags);

            // For all splits, automatically unlock this join if either of the splits were unlocked
            for(ConversationBuilder split : splits) {
                // In the first conversation, lock all other splits
                conversations.addAll(split.conversations);
                Conversation conversation = split.conversations.get(0);
                conversation.tagsToLock.addAll(allTags);
                // In the last conversation, unlock join tag
                conversation = split.conversations.get(split.conversations.size() - 1);
                conversation.tagsToUnlock.add(joinName);
            }

            // Clear
            splits.clear();
            splitTags.clear();

            // Create join
            current = new Conversation();
            current.tags.add(joinName);
            current.tagsToLock.add(joinName);
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
                current = new Conversation();
                current.tags.addAll(tags);
                conversations.add(current);

                if(!isRepeatable) {
                    String nextLinkName = linkPrefix + linkCounter;
                    linkCounter++;

                    current.tags.add("!" + nextLinkName);
                    current.tagsToUnlock.add(nextLinkName);
                }
            }
            else if(conversations.size() == 1) {
                // Create a new link to close up first conversation
//                String linkName = linkPrefix + linkCounter;
//                linkCounter++;
//                current.tags = appendString(current.tags, "!" + linkName);
                // Create a new link for second conversation
                String nextLinkName = linkPrefix + linkCounter;
                linkCounter++;
//                current.tags_to_unlock = appendString(current.tags_to_unlock, linkName + ", " + nextLinkName);
                current.tagsToUnlock.add(nextLinkName);

                current = new Conversation();
                current.tags.add(nextLinkName);
                current.tagsToLock.add(nextLinkName);
                conversations.add(current);
            }
            else {
                // Create a new link for next conversation
                String nextLinkName = linkPrefix + linkCounter;
                linkCounter++;

                current.tagsToUnlock.add(nextLinkName);

                current = new Conversation();
                current.tags.add(nextLinkName);
                current.tagsToLock.add(nextLinkName);
                conversations.add(current);
            }
            // Indicate tags has not been modified yet
            isTagsModified = false;
        }

        public void choice(UserMessage[] messages) {
            validatePreviousSplits();
            // Choices will always start a new conversation
            if(current == null || !current.userMessages.isEmpty()  || !current.senderMessages.isEmpty())
                push();
            current.userMessages.addAll(Arrays.asList(messages));
            currentSender = "user";
            // Cleanup messages
            for(UserMessage message : messages)
                message.message = message.message.trim();
        }

        public void reply(SenderMessage[] messages) {
            for(SenderMessage message : messages) {
                message.npc = "user";
            }
            response(messages);
        }

        public void response(SenderMessage[] messages) {
            validatePreviousSplits();
            // Update message times
            for(SenderMessage message : messages) {
                // Live
                if (!message.npc.equals("user") && message.idleTime == 0 && message.typingTime == 0) {     // live user responses have no timing
                    // Check if new switching person
                    if(!currentSender.equals(message.npc))
                        queuedIdleTime += switchTime.generate();
                    message.idleTime = queuedIdleTime + sentenceTime.generate();
                    queuedIdleTime = 0;

                    // Typing speed
                    int words = message.message.trim().split("\\s+").length;
                    message.typingTime = (1f / wordsPerMinute) * words;
                    message.typingTime += queuedTypingTime;
                    queuedTypingTime = 0;
                }

                currentSender = message.npc;
            }

            if(current == null || isTagsModified)
                push();         // first conversation or tags has been modified

            current.senderMessages.addAll(Arrays.asList(messages));
        }

        public void ignore_choice() {
            validatePreviousSplits();
            if(current != null && current.userMessages != null)
                current.isUserIgnored = true;
        }

        public void narrator(ScriptBuilder builder) {
            validatePreviousSplits();
            if(current == null)
                push();
            current.script = builder.build();
            push();
        }

        private void unlocks(String tags) {
            if(current == null)
                push();
            current.tagsToUnlock.add(tags);
            isTagsModified = true;          // indicate that cannot append sender messages together
        }

        private void locks(String tags) {
            if(current == null)
                push();
            current.tagsToLock.add(tags);
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
            current.tags.add(tags);
        }


        @Override
        public void onSheetEnded() {
            validatePreviousSplits();
        }

        private void validatePreviousSplits() {
            if(!splits.isEmpty())
                throw new RuntimeException("Missing join, joinAll or joinPersist for previous splits");
        }
    }


    public void add(ConversationBuilder builder) {
        // Reset selected npc and player
        ConversationBuilder.selectedPlayer = null;
        ConversationBuilder.selectedNpc = null;
        conversations.addAll(builder.conversations);
    }


}
