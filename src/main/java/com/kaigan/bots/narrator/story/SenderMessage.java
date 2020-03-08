package com.kaigan.bots.narrator.story;

import sengine.sheets.OnSheetEnded;
import sengine.sheets.ParseException;
import sengine.sheets.SheetFields;

@SheetFields(fields = { "message", "npc", "idleTime", "typingTime" })
public class SenderMessage implements OnSheetEnded {
    public String message;
    public String npc = StoryChannelBuilder.ConversationBuilder.selectedNpc;

    public float idleTime = 0;
    public float typingTime = 0;

    public void npc(String text) {
        npc = text.toLowerCase();        // origin is always case-insensitive
        StoryChannelBuilder.ConversationBuilder.selectedNpc = npc;      // set as default npc
    }

    @Override
    public void onSheetEnded() {
        if(npc == null)
            throw new ParseException("npc not set");
    }
}
