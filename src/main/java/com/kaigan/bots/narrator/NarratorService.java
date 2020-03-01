package com.kaigan.bots.narrator;

import com.kaigan.bots.narrator.save.SaveObject;
import net.dv8tion.jda.api.events.guild.member.GuildMemberJoinEvent;
import net.dv8tion.jda.api.events.guild.member.GuildMemberLeaveEvent;
import net.dv8tion.jda.api.events.guild.member.update.GuildMemberUpdateNicknameEvent;
import net.dv8tion.jda.api.events.message.guild.GuildMessageReceivedEvent;
import net.dv8tion.jda.api.events.message.guild.react.GuildMessageReactionAddEvent;
import net.dv8tion.jda.api.events.message.guild.react.GuildMessageReactionRemoveEvent;

import java.util.Map;

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

    default boolean processMessage(Narrator bot, GuildMessageReceivedEvent event, ProcessedMessage message) {
        return false;       // passthru
    }

    default boolean processReactionAdded(Narrator bot, GuildMessageReactionAddEvent event) {
        return false;       // passthru
    }

    default boolean processReactionRemoved(Narrator bot, GuildMessageReactionRemoveEvent event) {
        return false;       // passthru
    }

    default boolean processNickChange(Narrator bot, GuildMemberUpdateNicknameEvent event) {
        return false;       // passthru
    }

    default boolean processMemberJoined(Narrator bot, GuildMemberJoinEvent event) {
        return false;       // passthru
    }

    default boolean processMemberLeft(Narrator bot, GuildMemberLeaveEvent event) {
        return false;       // passthru
    }

    default void save(Map<String, SaveObject> save) {
        // nothing
    }

}
