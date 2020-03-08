package com.kaigan.bots.narrator.story;

import sengine.sheets.OnSheetEnded;
import sengine.sheets.ParseException;
import sengine.sheets.SheetFields;
import sengine.sheets.SheetParser;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@SheetFields(fields = { "message", "player", "tags" })
public class UserMessage implements OnSheetEnded {
    public String message;
    public String player = StoryChannelBuilder.ConversationBuilder.selectedPlayer;
    public final List<String> tags = new ArrayList<>();

    public void player(String text) {
        player = text.toLowerCase();        // origin is always case-insensitive
        StoryChannelBuilder.ConversationBuilder.selectedPlayer = player;
    }

    public void tags(String csv) { tags.addAll(Arrays.asList(SheetParser.splitStringCSV(csv))); }

    @Override
    public void onSheetEnded() {
        if(player == null)
            throw new ParseException("player not set");
    }
}
