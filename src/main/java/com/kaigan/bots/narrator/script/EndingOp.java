package com.kaigan.bots.narrator.script;

import sengine.sheets.ParseException;
import sengine.sheets.SheetFields;

@SheetFields(requiredFields = { "player" })
public class EndingOp extends Operation {

    public String[] player;

    // optional
    public int color;
    public String message;

    public void publish(String color, String message) {
        if(!message.contains("%(participant)"))
            throw new ParseException("message must reference %(participant)");
        this.message = message;
        this.color = (int) Long.parseLong(color, 16);;
    }

    @Override
    protected void op(ScriptContext context) {
        context.instance.playerEnding(player, color, message);
    }
}
