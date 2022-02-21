package com.kaigan.bots.narrator;

import net.dv8tion.jda.api.events.guild.member.GuildMemberJoinEvent;
import net.dv8tion.jda.api.events.guild.member.update.GuildMemberUpdateNicknameEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.message.react.MessageReactionAddEvent;
import net.dv8tion.jda.api.events.message.react.MessageReactionRemoveEvent;

public interface NarratorService {

    default long onServiceStart(Narrator bot) {
        return -1;          // don't schedule next process
    }

    default long processService(Narrator bot) {
        return -1;          // don't schedule next process
    }

    default boolean onServiceStop(Narrator bot) {
        return true;        // allow stop
    }

    default boolean processPrivateMessage(Narrator bot, MessageReceivedEvent event, ProcessedMessage message) {
        return false;       // passthru
    }

    default boolean processPrivateReactionAdded(Narrator bot, MessageReactionAddEvent event) {
        return false;       // passthru
    }

    default boolean processPrivateReactionRemoved(Narrator bot, MessageReactionRemoveEvent event) {
        return false;       // passthru
    }


    default boolean processMessage(Narrator bot, MessageReceivedEvent event, ProcessedMessage message) {
        return false;       // passthru
    }

    default boolean processReactionAdded(Narrator bot, MessageReactionAddEvent event) {
        return false;       // passthru
    }

    default boolean processReactionRemoved(Narrator bot, MessageReactionRemoveEvent event) {
        return false;       // passthru
    }

    default boolean processNickChange(Narrator bot, GuildMemberUpdateNicknameEvent event) {
        return false;       // passthru
    }

    default boolean processMemberJoined(Narrator bot, GuildMemberJoinEvent event) {
        return false;       // passthru
    }
}
