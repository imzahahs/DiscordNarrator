package com.kaigan.bots.narrator.story;

import com.kaigan.bots.narrator.Narrator;
import com.kaigan.bots.narrator.NarratorProvider;
import net.dv8tion.jda.internal.utils.Checks;
import sengine.sheets.OnSheetEnded;
import sengine.sheets.ParseException;
import sengine.sheets.SheetFields;
import sengine.sheets.SheetStack;

import java.util.ArrayList;
import java.util.List;

@SheetFields(fields = {
        "title", "author", "description",
        "npcs", "players"
}, requiredFields = {
        "title", "author", "description",
        "players"
})
public class StoryBuilder implements NarratorProvider, OnSheetEnded {

    @SheetFields(requiredFields = { "name", "color", "profilePic" })
    public static class NpcBuilder {
        public String name;
        public int color;
        public String profilePic;

        public void name(String name) {
            if(name.equalsIgnoreCase(StoryChannelBuilder.ORIGIN_NARRATOR))
                throw new ParseException("Narrator is a reserved name");
            this.name = name;
        }

        public void color(String color) {
            this.color = (int) Long.parseLong(color, 16);
        }

        public void profilePic(String url) {
            profilePic = url;

            // Download file
            SheetStack.first(NarratorProvider.class).getBot().getFile(url);
        }
    }

    @SheetFields(requiredFields = { "name", "color", "profilePic", "description" })
    public static class PlayerBuilder extends NpcBuilder {
        public String description;
    }

    public transient Narrator bot;

    public String title;
    public String author;
    public String description;

    public NpcBuilder[] npcs;

    public PlayerBuilder[] players;

    public List<StoryChannelBuilder> channels = new ArrayList<>();

    public StoryBuilder() {
        // no-arg constructor for mass serializer
    }

    public StoryBuilder(Narrator bot) {
        this.bot = bot;
    }

    public synchronized void channel(StoryChannelBuilder channel) {
        channels.add(channel);
    }

    @Override
    public Narrator getBot() {
        return bot;
    }


    @Override
    public void onSheetEnded() {
        Checks.notEmpty(channels, "channels");
        Checks.notEmpty(players, "players");
    }
}
