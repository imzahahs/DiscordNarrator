package com.kaigan.bots.narrator.script;

import sengine.sheets.ParseException;
import sengine.sheets.SheetFields;

@SheetFields(fields = { "name", "message" }, requiredFields = { "name" })
public class RemovePlayer extends Operation {

    public String name;
    public String message;

    public void message(String message) {
        if(!message.contains("%(player)") || !message.contains("%(participant)"))
            throw new ParseException("message must reference %(player) and %(participant)");
        this.message = message;
    }

    @Override
    protected void op(ScriptContext context) {
        context.channel.removePlayer(name, message);
    }
}
