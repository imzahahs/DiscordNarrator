package com.kaigan.bots.narrator.story;

import com.kaigan.bots.narrator.SheetMessageBuilder;
import sengine.sheets.ParseException;
import sengine.sheets.SheetFields;

@SheetFields(fields = { "message", "npc", "idleTime", "typingTime", "embed", "file" })
public class SenderMessage extends SheetMessageBuilder {
    public String npc = StoryChannelBuilder.ConversationBuilder.selectedNpc;

    public float idleTime = 0;
    public float typingTime = 0;

    public void npc(String text) {
        npc = text.toLowerCase();        // origin is always case-insensitive
        StoryChannelBuilder.ConversationBuilder.selectedNpc = npc;      // set as default npc
    }

    @Override
    public void onSheetEnded() {
        super.onSheetEnded();
        if(npc == null)
            throw new ParseException("npc not set");
    }
}
