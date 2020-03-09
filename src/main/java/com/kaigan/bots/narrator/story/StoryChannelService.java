package com.kaigan.bots.narrator.story;

import com.kaigan.bots.narrator.Narrator;
import com.kaigan.bots.narrator.NarratorService;
import com.kaigan.bots.narrator.script.ScriptContext;
import net.dv8tion.jda.api.MessageBuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.events.message.guild.react.GuildMessageReactionAddEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.*;
import java.util.stream.Collectors;

public class StoryChannelService implements NarratorService, ScriptState.OnChangeListener<Object> {
    private static final Logger log = LogManager.getLogger("StoryChannelService");

    private class ReplySelection {
        final String botName;
        final Member player;
        final TextChannel channel;
        final List<UserMessage> selection;
        final List<Emote> choiceEmotes;
        final Message selectionMessage;

        ReplySelection(String botName) {
            this.botName = botName;
            this.player = instance.players.get(botName);
            if(player == null)
                log.error("Player '{}' not found in channel '{}'", botName, builder.name);
            channel = resolveSenderChannel(botName);
            // Filter selection for only this player
            selection = tree.availableUserMessages.stream()
                    .filter(userMessage -> userMessage.player.contentEquals(botName))
                    .collect(Collectors.toList());
            // Build message
            StringBuilder sb = new StringBuilder();
            Narrator narrator = instance.storyService.bot;
            choiceEmotes = new ArrayList<>(selection.size());
            for(int c = 0; c < selection.size(); c++) {
                Emote emote = narrator.getChoiceEmote(c).get();
                choiceEmotes.add(emote);
                if(c > 0)
                    sb.append("\n");
                sb.append(narrator.format(instance.storyService.config.chooseReplyRowFormat,
                        "choiceEmote", emote.getAsMention(),
                        "message", selection.get(c).message
                ));
            }
            selectionMessage = instance.storyService.config.chooseReplyMessage.build(narrator, channel,
                    "selection", sb.toString().trim(),
                    "player", player != null ? player.getAsMention() : narrator.guild.getPublicRole().getAsMention()        // default to @everyone if unable to resolve player
            ).complete();
            // Add choices
            for(int c = 0; c < selection.size(); c++) {
                // Resolve emote
                Emote emote = channel.getGuild().getEmoteById(choiceEmotes.get(c).getId());
                narrator.queue(() -> selectionMessage.addReaction(emote), log, "Adding choice emote to reply selection message");
            }
        }
    }

    private final StoryInstanceService instance;
    private final StoryChannelBuilder builder;

    private final DialogueTree tree;

    private TextChannel channel;

    private int currentMessage = -1;
    private long tTypingScheduled = Long.MAX_VALUE;
    private long tNextMessageScheduled = -1;
    private long tNextTimedReplyScheduled = Long.MAX_VALUE;
    private long tReplySelectionScheduled = Long.MAX_VALUE;
    private boolean hasCheckedDialogueTree = false;

    private final Map<String, TextChannel> storyBotChannels = new HashMap<>();
    private final Set<String> players = new HashSet<>();

    private List<ReplySelection> replySelections = Collections.emptyList();

    public void addNpc(String npc, String message) {
        // Check if already added
        npc = npc.toLowerCase();
        if(instance.players.containsKey(npc)) {
            log.error("Npc {} to be added is a player", npc);
            return;
        }
        if(storyBotChannels.containsKey(npc)) {
            log.error("Npc {} already added to {}", npc, builder.name);
            return;
        }
        // Get Storybot
        Narrator narrator = instance.storyService.bot;
        StoryBot storyBot = instance.storyBots.get(npc);
        // Add permission
        Member botMember = narrator.guild.getMemberById(storyBot.getMemberId());
        narrator.queue(() -> channel.upsertPermissionOverride(botMember).setAllow(Permission.MESSAGE_READ, Permission.MESSAGE_WRITE),
                log, "Allow npc story bot access to channel " + builder.name
        );
        // Resolve bot channel
        TextChannel botChannel = storyBot.getTextChannelById(channel.getId());
        storyBotChannels.put(npc, botChannel);
        // Send message
        narrator.queue(() -> channel.sendMessage(narrator.format(message,
                "npc", botMember.getAsMention()
        )), log, "Sending npc added message");
    }

    public void removeNpc(String npc, String message) {
        // Check if added
        npc = npc.toLowerCase();
        if(players.contains(npc)) {
            log.error("Npc {} to be removed is a player", npc);
            return;
        }
        if(npc.contentEquals(StoryChannelBuilder.ORIGIN_NARRATOR)) {
            log.error("Cannot remove narrator");
            return;
        }
        TextChannel botChannel = storyBotChannels.remove(npc);
        if(botChannel == null) {
            log.error("Npc {} not found in {}", npc, builder.name);
            return;
        }
        // Send message
        Narrator narrator = instance.storyService.bot;
        Member botMember = narrator.guild.getMemberById(botChannel.getGuild().getSelfMember().getId());
        narrator.queue(() -> channel.sendMessage(narrator.format(message,
                "npc", botMember.getAsMention()
        )), log, "Sending npc left message");
        // Remove permission
        narrator.queue(() -> channel.getPermissionOverride(botMember).delete(),
                log, "Remove npc story bot access to channel " + builder.name
        );
    }

    public void addPlayer(String player, String message) {
        // Check if already added
        player = player.toLowerCase();
        if(players.contains(player)) {
            log.error("Player {} already added to {}", player, builder.name);
            return;
        }
        // Get player
        Member participant = instance.players.get(player);
        if(participant == null) {
            log.error("Unknown player {} to be added to {}", player, builder.name);
            return;
        }
        // Get Storybot
        Narrator narrator = instance.storyService.bot;
        StoryBot storyBot = instance.storyBots.get(player);
        // Add permissions for both
        narrator.queue(() -> channel.upsertPermissionOverride(participant).setAllow(Permission.MESSAGE_READ, Permission.MESSAGE_WRITE),
                log, "Allow participant access to channel " + builder.name
        );
        Member botMember = narrator.guild.getMemberById(storyBot.getMemberId());
        narrator.queue(() -> channel.upsertPermissionOverride(botMember).setAllow(Permission.MESSAGE_READ, Permission.MESSAGE_WRITE),
                log, "Allow player story bot access to channel " + builder.name
        );
        // Resolve bot channel
        TextChannel botChannel = storyBot.getTextChannelById(channel.getId());
        storyBotChannels.put(player, botChannel);
        players.add(player);
        // Send message
        narrator.queue(() -> channel.sendMessage(narrator.format(message,
                "participant", participant.getAsMention(),
                "player", botMember.getAsMention()
        )), log, "Sending player added message");
    }

    public void removePlayer(String player, String message) {
        // Check if added
        player = player.toLowerCase();
        if(!players.remove(player)) {
            log.error("Player {} to be removed not found in {}", player, builder.name);
            return;
        }
        TextChannel botChannel = storyBotChannels.remove(player);
        // Send message
        Narrator narrator = instance.storyService.bot;
        Member participant = instance.players.get(player);
        Member botMember = narrator.guild.getMemberById(botChannel.getGuild().getSelfMember().getId());
        narrator.queue(() -> channel.sendMessage(narrator.format(message,
                "player", botMember.getAsMention(),
                "participant", participant.getAsMention()
        )), log, "Sending player left message");
        // Remove permissions
        narrator.queue(() -> channel.getPermissionOverride(botMember).delete(),
                log, "Remove npc story bot access to channel " + builder.name
        );
        narrator.queue(() -> channel.getPermissionOverride(participant).delete(),
                log, "Remove npc story bot access to channel " + builder.name
        );
    }

    StoryChannelService(StoryInstanceService instance, StoryChannelBuilder builder) {
        this.instance = instance;
        this.builder = builder;

        tree = new DialogueTree(instance.states, builder);

        // Monitor script changes
        instance.states.addOnChangeListener(builder.name + ".", Object.class, this);
    }

    @Override
    public long onServiceStart(Narrator bot) {
        if(channel != null)
            return -1;      // Already initialized

        // Else initialize, create channel
        Category storyCategory = bot.guild.getCategoriesByName(instance.storyService.config.storyCategory, false).get(0);
        channel = bot.guild.createTextChannel(builder.name)
                // Topic
                .setTopic(builder.topic)
                // Under story category
                .setParent(storyCategory)
                // Deny everyone
                .addPermissionOverride(bot.guild.getPublicRole(), null, EnumSet.of(Permission.MESSAGE_READ))
                // Allow narrator by default
                .addPermissionOverride(bot.guild.getSelfMember(), EnumSet.of(Permission.MESSAGE_READ), null)
                .complete();

        // Add narrator channel lookup
        storyBotChannels.put(StoryChannelBuilder.ORIGIN_NARRATOR, channel);

        // Start process loop
        return 0;
    }


    private TextChannel resolveSenderChannel(String origin) {
        // Lookup
        TextChannel originChannel = storyBotChannels.get(origin);
        if(originChannel == null) {
            // Log but default back to narrator
            log.error("Story bot '{}' not found in channel '{}'", origin, builder.name);
            originChannel = channel;
            storyBotChannels.put(origin, originChannel);
        }
        // Else return
        return originChannel;
    }


    @Override
    public long processService(Narrator bot) {
        long currentTime = System.currentTimeMillis();

        out:
        while(true) {
            if (tree.current != null) {
                // There is a conversation going on, check if its time
                while (true) {
                    if (currentTime >= tTypingScheduled) {
                        // Send typing event
                        SenderMessage message = tree.current.senderMessages.get(currentMessage);
                        TextChannel originChannel = resolveSenderChannel(message.npc);
                        originChannel.sendTyping().queue();     // ignore success or failure
                        tTypingScheduled = currentTime + instance.storyService.config.storyBotTypingInterval;
                    }
                    if (currentTime < tNextMessageScheduled)
                        break out;      // Still not time to send message
                    // Else time to show message or prepare first message
                    if (currentMessage != -1) {
                        SenderMessage message = tree.current.senderMessages.get(currentMessage);
                        if (message.message != null && !message.message.isEmpty()) {
                            // Send message
                            TextChannel originChannel = resolveSenderChannel(message.npc);
                            bot.queue(() -> originChannel.sendMessage(new MessageBuilder(message.message).build()), log, "Send message to channel " + channel.getName());
                            instance.resetInstanceTimeout();            // Reset instance timeout
                        }
                    }
                    // Else time for first message or already showed last message
                    currentMessage++;
                    // Try next message
                    if (currentMessage >= tree.current.senderMessages.size()) {
                        // Run script if available
                        try {
                            if(tree.current.script != null) {
                                new ScriptContext(this, instance, instance.storyService).insert(tree.current.script).run();
                            }
                        } catch (Throwable e) {
                            log.error("Unable to run script for current conversation", e);
                        }
                        // Finished messages, inform tree that conversation ended
                        tree.finishCurrent();
                        // Reset
                        currentMessage = -1;
                        tNextMessageScheduled = -1;
                        hasCheckedDialogueTree = false;
                        tTypingScheduled = Long.MAX_VALUE;
                        break;        // finished next message
                    } else {
                        // Continue next message
                        SenderMessage message = tree.current.senderMessages.get(currentMessage);
                        if(message.typingTime > 0f)
                            tTypingScheduled = currentTime + (long)(message.idleTime * instance.chatTimingMultiplier * 1000f);
                        tNextMessageScheduled = currentTime + (long)((message.idleTime + message.typingTime) * instance.chatTimingMultiplier * 1000f);
                    }
                }
                // Can come here only if finished a conversation
            } else if (currentTime > tReplySelectionScheduled) {
                tReplySelectionScheduled = Long.MAX_VALUE;
                // Send reply selection
                refreshReplySelection();
                break out;
            } else if (currentTime > tNextTimedReplyScheduled) {
                // It's time to reply
                tNextTimedReplyScheduled = Long.MAX_VALUE;         // Clear timed reply
                reply(tree.availableUserMessages.get(tree.timedUserMessageIndex).message);
            } else if (hasCheckedDialogueTree)
                break out;

            // Else need to find a conversation
            tree.refreshCurrent();
            // User message could be invalidated here, so have to reset scheduled time message. The downside is whenever the tree refreshes, scheduled time message would have to start again
            tNextTimedReplyScheduled = Long.MAX_VALUE;         // Clear timed reply
            tReplySelectionScheduled = Long.MAX_VALUE;

            // If there are user messages, inform app to update
            if(!tree.availableUserMessages.isEmpty()) {
                // Conversation can be made, but requires user to type a message, but queue refresh as other conversation can be unlocked due to external triggers
                hasCheckedDialogueTree = true;
                // Queue sending of reply selection
                tReplySelectionScheduled = currentTime + instance.storyService.config.storyReplySelectionDelay;
                if(tree.timedUserMessageIndex != -1)
                    tNextTimedReplyScheduled = currentTime + (long)(tree.timedUserMessageDelay * instance.chatTimingMultiplier * 1000f);
                break;
            }
            else if(tree.current == null) {
                // No conversation can be made now
                hasCheckedDialogueTree = true;
                cancelReplySelection();
                break;
            }
            // Else a conversation has been made current, display messages
            cancelReplySelection();
        }

        return instance.storyService.config.storyChannelTimestep;
    }

    private void refreshReplySelection() {
        // Check if previous selection is compatible
        List<UserMessage> currentSelection = replySelections.stream()
                .flatMap(replySelection -> replySelection.selection.stream())
                .collect(Collectors.toList());
        if(currentSelection.containsAll(tree.availableUserMessages) && currentSelection.size() == tree.availableUserMessages.size())
            return;     // previous reply selection is still valid
        // Else cancel previous if any and send new reply selection
        cancelReplySelection();
        replySelections = tree.availableUserMessages.stream()
                // For each unique player name, send a reply selection
                .map(userMessage -> userMessage.player)
                .distinct()
                // Resolve player and send message now
                .map(ReplySelection::new)
                .collect(Collectors.toList());

        instance.resetInstanceTimeout();            // Reset instance timeout
    }

    private void cancelReplySelection() {
        // Remove previous selection messages
        for(ReplySelection replySelection : replySelections)
            replySelection.selectionMessage.delete().queue();
        replySelections = Collections.emptyList();
    }

    @Override
    public boolean processReactionAdded(Narrator bot, GuildMessageReactionAddEvent event) {
        if(event.getChannel() != channel)
            return false;       // not monitored
        // Check monitored messages
        ReplySelection selection = replySelections.stream()
                .filter(replySelection -> replySelection.selectionMessage.getId().equals(event.getMessageId()))
                .findFirst().orElse(null);
        if(selection == null)
            return false;       // not monitored
        // Check if the correct member made the choice
        if(selection.player != null && selection.player != event.getMember())
            return false;
        // Get selection index
        int index = selection.choiceEmotes.indexOf(event.getReactionEmote().getEmote());
        if(index == -1)
            return false;       // not monitored
        // Else send this reply
        reply(selection.selection.get(index).message);
        return false;
    }

    private void reply(String userMessage) {
        int index = tree.makeCurrent(userMessage);
        if(index == -1)
            return;       // contact is not expecting this result, UB
        // Else accepted, collapse all selections
        tNextTimedReplyScheduled = Long.MAX_VALUE;
        // Else recognize user's message, only if it's not ignored by the conversation and it's not a timed reply
        UserMessage message = tree.current.userMessages.get(index);
        if(tree.current.isUserIgnored || message.message.startsWith(DialogueTree.DIALOG_TIMER))
            message = null;
        // Delete or update user message
        Narrator narrator = instance.storyService.bot;
        for(ReplySelection selection : replySelections) {
            if(selection.selection.contains(message)) {
                // Edit message
                UserMessage finalMessage = message;
                narrator.queue(() -> selection.selectionMessage.editMessage(finalMessage.message), log, "Edit reply selection message with final reply");
                narrator.queue(() -> selection.selectionMessage.clearReactions(), log, "Clear choice reactions for selection message");
            }
            else
                narrator.queue(selection.selectionMessage::delete, log, "Delete expired selection message");
        }
        // Done
        replySelections = Collections.emptyList();
    }

    @Override
    public boolean onServiceStop(Narrator bot) {
        // Delete channel
        bot.queue(() -> channel.delete(), log, "Delete story channel " + builder.name);
        return true;
    }

    @Override
    public void onChanged(String name, Object var, Object prev) {
        // Invalidate tree
        hasCheckedDialogueTree = false;
    }

}
