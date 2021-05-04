package com.kaigan.bots.narrator.script;

import sengine.sheets.ParseException;
import sengine.sheets.SheetFields;

@SheetFields(fields = { "name", "message" }, requiredFields = { "name" })
public class AddNpc extends Operation {

    public String name;
    public String message;

    public void message(String message) {
        if(!message.contains("%(npc)"))
            throw new ParseException("message must reference %(npc)");
        this.message = message;
    }

    @Override
    protected void op(ScriptContext context) {
        context.channel.addNpc(name, message);
    }
}
