package com.kaigan.bots.narrator.story;

import com.kaigan.bots.narrator.Narrator;
import com.kaigan.bots.narrator.NarratorService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

class StoryBot implements NarratorService {
    private static final Logger log = LogManager.getLogger("StoryBot");

    private static final String SAVE_STATE_NAME = "StoryBot.state.";

    public static class SaveState {
        public String name;
        public int color;
        public String profilePic;
        public long lastProfilePicChange = Long.MIN_VALUE;
    }

    private final StoryService storyService;
    private final String token;
    private final String roleName;

    private JDA jda;
    private Guild guild;

    private SaveState state;

    private final List<StoryInstanceService> instances = new ArrayList<>();

    String getMemberId() {
        return guild.getSelfMember().getId();
    }

    TextChannel getTextChannelById(String id) {
        return guild.getTextChannelById(id);
    }

    boolean isOnline() {
        return jda != null;
    }

    String getName() {
        return state.name;
    }

    String getProfilePic() {
        return state.profilePic;
    }

    long getProfilePicAge() {
        return System.currentTimeMillis() - state.lastProfilePicChange;
    }

    void release(StoryInstanceService instance) {
        instances.remove(instance);
    }

    boolean acquire(StoryInstanceService instance, StoryBuilder.NpcBuilder requestedState, boolean reconfigure) {
        boolean isCompatible = Objects.equals(requestedState.name, state.name)
                && Objects.equals(requestedState.color, state.color)
                && Objects.equals(requestedState.profilePic, state.profilePic);

        if(!isCompatible) {
            if(!reconfigure)
                return false;       // not compatible and cant reconfigure, so cant acquire
            // Else try to reconfigure
            if(!isFree())
                return false;       // cant reconfigure because this bot is being used by some instances
            Narrator narrator = storyService.bot;
            try {
                // Need to start bot to configure
                startBot();

                // Change name if required
                if(!Objects.equals(requestedState.name, state.name)) {
                    Member bot = narrator.guild.getMemberById(jda.getSelfUser().getId());
                    bot.modifyNickname(requestedState.name).complete();
                    state.name = requestedState.name;
                }

                // Change color if required
                if(!Objects.equals(requestedState.color, state.color)) {
                    Role role = narrator.guild.getRolesByName(roleName, false).get(0);
                    role.getManager().setColor(requestedState.color).complete();
                    state.color = requestedState.color;
                }

                // Change profile pic if required
                if(!Objects.equals(requestedState.profilePic, state.profilePic)) {
                    // Get extension from url
                    int index = requestedState.profilePic.lastIndexOf('.');
                    Icon.IconType iconType = Icon.IconType.JPEG;
                    if(index != -1) {
                        String extension = requestedState.profilePic.substring(index + 1);
                        iconType = Icon.IconType.fromExtension(extension);
                        if(iconType == Icon.IconType.UNKNOWN)
                            iconType = Icon.IconType.JPEG;      // assume jpeg
                    }

                    // Load avatar
                    Icon avatar = Icon.from(new File(narrator.getFile(requestedState.profilePic)), iconType);

                    // Attempt to change profile pic
                    jda.getSelfUser().getManager().setAvatar(avatar).complete();
                    state.profilePic = requestedState.profilePic;
                    state.lastProfilePicChange = System.currentTimeMillis();
                }

                // Update save
                narrator.putSave(SAVE_STATE_NAME + token, state);

            } catch (Throwable e) {
                log.error("Unable to reconfigure bot:\nName: {}\nColor: {}\nProfile Pic: {}",
                        requestedState.name,
                        requestedState.color,
                        requestedState.profilePic,
                        e
                );
                return false;   // failed
            }
        }
        else
            startBot();                 // Start bot

        // Else configured, recognize instance
        instances.add(instance);

        return true;
    }



    StoryBot(StoryService storyService, String token, String roleName) {
        this.storyService = storyService;
        this.token = token;
        this.roleName = roleName;

        // Get save state
        state = storyService.bot.<SaveState>getSave(SAVE_STATE_NAME + token).orElseGet(SaveState::new);
    }

    private void startBot() {
        if(jda != null)
            return;     // already started

        // Login and prepare all data
        JDA parent = storyService.bot.jda;

        try {
            log.info("Starting {}", roleName);

            jda = new JDABuilder(token)
//                    .setStatus(OnlineStatus.INVISIBLE)
                    // Piggy back on parent pool
                    .setCallbackPool(parent.getCallbackPool(), false)
                    .setGatewayPool(parent.getGatewayPool(), false)
                    .setRateLimitPool(parent.getRateLimitPool(), false)
                    .build().awaitReady();

            // Log guilds discovered for security (someone is able to get the bot invite screen, not sure if it can be added though)
            jda.getGuilds().forEach(guild -> log.info("Found guild: {}", guild.getName()));

            // Get guild
            guild = jda.getGuilds().stream().filter(guild -> guild.getName().equals(storyService.bot.serverName)).findAny().orElseThrow(() -> {
                throw new RuntimeException("Failed to find guild");
            });

            // Request timeout check
            storyService.bot.scheduleService(this, storyService.config.storyBotTimeout);

        } catch (Throwable e) {
            stopBot();
            throw new RuntimeException("Unable to start story bot", e);
        }
    }

    private void stopBot() {
        if(jda != null) {
            log.info("Stopping {}", roleName);

            jda.shutdown();
            jda = null;
            guild = null;
        }
    }

    @Override
    public long processService(Narrator bot) {
        if(isFree()) {
            stopBot();
            return -1;      // stop timeout check
        }

        // Schedule next timeout check
        return storyService.config.storyBotTimeout;
    }

    boolean isFree() {
        return instances.isEmpty();
    }
}
