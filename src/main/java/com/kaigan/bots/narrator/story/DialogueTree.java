package com.kaigan.bots.narrator.story;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Created by Azmi on 18/7/2016.
 */
public class DialogueTree {
    private static final Logger log = LogManager.getLogger("DialogueTree");

    public static final String TAG_IDLE = "IDLE";       // true when there are no available conversations

    public static final String KEYBOARD_PREFIX = "keyboard://";
    public static final String KEYBOARD_WILDCARD = "?";
    public static final String KEYBOARD_EQUALS = "=";
    public static final String DIALOG_TIMER = "timer://";


    /**
     * Soft-compares s1 and s2. s2 must contain all of s1 even if any in-between characters are ignored.
     * @param s1
     * @param s2
     * @return true if s2 contains all of s1
     */
    private static boolean compareWildcardReply(String s1, String s2) {
        s1 = s1.toLowerCase();
        s2 = s2.replaceAll("\\s+","").toLowerCase();

        int len1 = s1.length();

        int i1 = 0;
        int i2 = 0;
        boolean isContinuous = false;

        for(; i1 < len1; i1++) {
            char ch1 = s1.charAt(i1);
            if (Character.isWhitespace(ch1)) {
                isContinuous = false;
                continue;       // ignore whitespace
            }
            int next = s2.indexOf(ch1, i2);
            if(next == -1)
                return false;
            if (isContinuous && next != i2)     // for whole words, there must be no breaks
                return false;
            isContinuous = true;
            i2 = next + 1;
        }

        return true;
    }

    private static boolean compareStrictReply(String s1, String s2) {
        s1 = s1.replaceAll("\\s+","").toLowerCase();
        s2 = s2.replaceAll("\\s+","").toLowerCase();

        return s1.contentEquals(s2);
    }


    private enum TagsResult {
        DEPENDS_ON_IDLE,
        ALLOWED,
        NOT_ALLOWED
    }

    // Descriptor
    public final ScriptState states;
    public final String namespace;
    public final List<Conversation> conversations;

    // Current conversations
    public Conversation current = null;
    public final ArrayList<Conversation> available = new ArrayList<>();
    private final ArrayList<Conversation> tempIdle = new ArrayList<>();
    public final ArrayList<UserMessage> availableUserMessages = new ArrayList<>();
    public int timedUserMessageIndex = -1;
    public float timedUserMessageDelay = 0f;


    public int activeConversations() {
        if(current != null)
            return 1;
        else
            return available.size();
    }

    /**
     * Makes the conversation containing the specified user message current.
     */
    public int makeCurrent(String userMessage, String userName, boolean isSelection) {
        String typed = userMessage.trim();
        if(typed.isEmpty())
            return -1;      // dont accept empty string

        Conversation bestConversation = null;
        int bestMessageIndex = -1;

        out:
        for(Conversation conversation : available) {
            for(int c = 0; c < conversation.userMessages.size(); c++) {
                UserMessage message = conversation.userMessages.get(c);
                // Make sure this reply is available
                if(!availableUserMessages.contains(message))
                    continue;       // not allowed

                // Check if user is correct
                if(!message.player.equalsIgnoreCase(userName))
                    continue;

                boolean isWildcard = message.message.startsWith(KEYBOARD_PREFIX);

                if(!isSelection && isWildcard) {
                    String wildcardMessage = message.message.substring(KEYBOARD_PREFIX.length());
                    if(wildcardMessage.contentEquals(KEYBOARD_WILDCARD)) {
                        // Keep track of this but try to find better matches
                        bestConversation = conversation;
                        bestMessageIndex = c;
                    }
                    else if(wildcardMessage.startsWith(KEYBOARD_WILDCARD)) {
                        wildcardMessage = wildcardMessage.substring(KEYBOARD_WILDCARD.length());
                        if(compareWildcardReply(wildcardMessage, typed)) {
                            // Found perfect match
                            bestConversation = conversation;
                            bestMessageIndex = c;
                            break out;
                        }
                    }
                    else {
                        if (wildcardMessage.startsWith(KEYBOARD_EQUALS))
                            wildcardMessage = wildcardMessage.substring(KEYBOARD_EQUALS.length());
                        if (compareStrictReply(wildcardMessage, typed)) {
                            // Found perfect match
                            bestConversation = conversation;
                            bestMessageIndex = c;
                            break out;
                        }
                    }
                }
                else if(isSelection && typed.equals(message.message)) {
                    // Else just text and it matches
                    bestConversation = conversation;
                    bestMessageIndex = c;
                    break out;
                }
            }
        }

        // Check if found a match
        if(bestConversation != null) {
            current = bestConversation;
            available.clear();
            availableUserMessages.clear();
            timedUserMessageIndex = -1;
            timedUserMessageDelay = 0f;
            return bestMessageIndex;
        }

        // Else no match was found
        return -1;
    }

    /**
     * Finishes current conversation
     */
    public void finishCurrent() {
        if (current == null)
            return;         // UB or state reset
        // Lock tags
        for(String tag : current.tagsToLock)
            setTagState(tag, false);
        // Unlock tags
        for(String tag : current.tagsToUnlock)
            setTagState(tag, true);
        // Finish current
        current = null;
    }

    private boolean getTagState(String tag) {
        String qualifiedName;
        if(tag.contains("."))
            qualifiedName = tag;
        else
            qualifiedName = namespace + "." + tag;
        return states.get(qualifiedName, false);
    }

    private void setTagState(String tag, boolean state) {
        if(tag.contains("."))
            states.set(tag, state, true);         // notify only when modifying states of other namespaces, 20180818: replaced !tag.startsWith(namespace) with just true as it conflicts with similar namespace names
        else
            states.set(namespace + "." + tag, state, false);            // no need to modify as we already know
    }


    private TagsResult evaluateTags(TagsResult idleBehaviour, List<String> tags) {
        TagsResult evaluation = TagsResult.ALLOWED;
        for(String tag : tags) {
            if(tag == null || tag.isEmpty())
                continue;       // ignore
            boolean isNot = tag.charAt(0) == '!';
            if(isNot)
                tag = tag.substring(1);

            boolean value;

            // Get value of special tag IDLE
            if(tag.equals(TAG_IDLE)) {
                if (idleBehaviour == TagsResult.DEPENDS_ON_IDLE) {
                    evaluation = TagsResult.DEPENDS_ON_IDLE;
                    continue;
                }
                // Else use specified value
                value = idleBehaviour == TagsResult.ALLOWED;
            }
            else
                value = getTagState(tag);

            // Evaluate value
            if (isNot) {
                if (value)
                    return TagsResult.NOT_ALLOWED;
            } else if (!value)
                return TagsResult.NOT_ALLOWED;
        }

        return evaluation;
    }


    /**
     * Refresh current conversation status, updates current, available and availableUserMessages fields.<br />
     */
    public void refreshCurrent() {
        // Clear
        current = null;
        available.clear();
        tempIdle.clear();
        availableUserMessages.clear();
        timedUserMessageIndex = -1;
        timedUserMessageDelay = 0f;

        // Evaluate conversations in order
        for(Conversation conversation : conversations) {

            // Check if tags are all unlocked
            TagsResult evaluation = evaluateTags(TagsResult.DEPENDS_ON_IDLE, conversation.tags);
            if(evaluation == TagsResult.NOT_ALLOWED)
                continue;       // Tags for this conversation is still locked

            // Check if conversation tags depend on idle
            if(evaluation == TagsResult.DEPENDS_ON_IDLE) {
                tempIdle.add(conversation);
                continue;
            }

            // Else is allowed, evaluate if user messages tags are allowed as well
            evaluation = TagsResult.ALLOWED;
            for(UserMessage userMessage : conversation.userMessages) {
                if(!userMessage.tags.isEmpty()) {
                    // Evaluate specified tags
                    TagsResult userMessageEvaluation = evaluateTags(TagsResult.DEPENDS_ON_IDLE, userMessage.tags);
                    if(userMessageEvaluation == TagsResult.ALLOWED) {
                        evaluation = TagsResult.ALLOWED;        // Found an user message where tags allows it, mark conversation as available
                        break;
                    }
                    else if(userMessageEvaluation == TagsResult.DEPENDS_ON_IDLE)
                        evaluation = TagsResult.DEPENDS_ON_IDLE;
                    else if(userMessageEvaluation == TagsResult.NOT_ALLOWED && evaluation == TagsResult.ALLOWED)
                        evaluation = TagsResult.NOT_ALLOWED;
                }
                else {
                    // By default, no tags means is allowed
                    evaluation = TagsResult.ALLOWED;        // Found an user message without any condition, mark conversation as available
                    break;
                }
            }

            // Check if user message tags completely depend on idle
            if(evaluation == TagsResult.DEPENDS_ON_IDLE) {
                tempIdle.add(conversation);
                continue;
            }

            // Add to available conversations if allowed
            if(evaluation == TagsResult.ALLOWED)
                available.add(conversation);

            // Else not allowed
        }
        // Re-evaluate idles
        TagsResult idleBehaviour = available.isEmpty() ? TagsResult.ALLOWED : TagsResult.NOT_ALLOWED;
        if(!tempIdle.isEmpty()) {
            for(int c = 0; c < tempIdle.size(); c++) {
                Conversation conversation = tempIdle.get(c);

                // Check if tags are all unlocked
                TagsResult evaluation = evaluateTags(idleBehaviour, conversation.tags);
                if(evaluation == TagsResult.NOT_ALLOWED)
                    continue;       // Idle tag evaluation locks this conversation

                // Else is allowed, evaluate if user messages tags are allowed as well
                for(UserMessage userMessage : conversation.userMessages) {
                    if(!userMessage.tags.isEmpty()) {
                        // Evaluate specified tags
                        TagsResult userMessageEvaluation = evaluateTags(idleBehaviour, userMessage.tags);
                        if(userMessageEvaluation == TagsResult.ALLOWED) {
                            evaluation = TagsResult.ALLOWED;
                            break;
                        }
                        else if(userMessageEvaluation == TagsResult.NOT_ALLOWED)
                            evaluation = TagsResult.NOT_ALLOWED;
                    }
                    else {
                        // By default, no tags means is allowed
                        evaluation = TagsResult.ALLOWED;
                        break;
                    }
                }

                // Add to available conversations if allowed
                if(evaluation == TagsResult.ALLOWED)
                    available.add(conversation);

                // Else not allowed
            }
            // Clear
            tempIdle.clear();
        }
        if(available.isEmpty())
            return;     // no conversations available

        // Now, find the first conversation that does not require user messages (sender sends first)
        for(int c = 0; c < available.size(); c++) {
            Conversation conversation = available.get(c);
            if(conversation.userMessages.isEmpty()) {
                // Found a conversation where sender sends first, automatically use this as current
                current = conversation;
                available.clear();
                availableUserMessages.clear();
                break;
            }

            // Else, add for each user messages where tags are allowed
            for(UserMessage userMessage : conversation.userMessages) {
                if(userMessage.tags.isEmpty() || evaluateTags(idleBehaviour, userMessage.tags) == TagsResult.ALLOWED)
                    availableUserMessages.add(userMessage);
            }
        }
        // Done

        // If there are user messages, check for timed messages
        for(int c = 0; c < availableUserMessages.size(); c++) {
            String userMessage = availableUserMessages.get(c).message;
            if(!userMessage.startsWith(DIALOG_TIMER))
                continue;       // not timed message
            // Parse time
            float time;
            try {
                time = Float.parseFloat(userMessage.substring(DIALOG_TIMER.length()));
            } catch (Throwable e) {
                // Ignore
                log.error("Failed to parse timed user message: " + userMessage);
                continue;
            }
            // Find the earliest timed reply
            if(timedUserMessageIndex == -1 || time < timedUserMessageDelay) {
                timedUserMessageIndex = c;
                timedUserMessageDelay = time;
            }
        }
    }


    /**
     * Helper method equivalent to availableUserMessages.size() > 0
     * @return boolean
     */
    public boolean isUserMessagesAvailable() {
        return availableUserMessages.size() > 0;
    }

    /**
     * Initializes the keyboard with available user messages that can be typed.
     */
//    public void refreshAvailableUserMessages(Keyboard keyboard) {
//        // If there are user messages, populate keyboard's auto complete
//        if (!availableUserMessages.isEmpty()) {
//            // Compile a list of visible and hidden autocompletes
//            ArrayList<String> autoCompletes = new ArrayList<>();
//            ArrayList<String> hiddenAutoCompletes = new ArrayList<>();
//            for (int c = 0; c < availableUserMessages.size(); c++) {
//                UserMessage message = availableUserMessages.get(c);
//                if (!message.message.startsWith(Globals.DIALOG_TIMER)) {
//                    if (message.isHidden)
//                        hiddenAutoCompletes.add(message.message);
//                    else
//                        autoCompletes.add(message.message);
//                }
//            }
//            keyboard.resetAutoComplete(autoCompletes, hiddenAutoCompletes);
//        }
//        else
//           keyboard.resetAutoComplete();           // Else clear keyboard auto completes
//    }

    public DialogueTree(ScriptState states, StoryChannelBuilder model) {
        this.states = states;

        namespace = model.name;

        // Compile all conversations
        conversations = model.conversations;
    }
}
