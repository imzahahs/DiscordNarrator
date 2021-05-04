package com.kaigan.bots.narrator.script;

import sengine.sheets.SheetFields;
import sengine.sheets.SheetParser;

@SheetFields(fields = { "tags" })
public class ResetOp extends Operation {

    public String[] tags;

    public void tags(String tags) {
        this.tags = SheetParser.splitStringCSV(tags);
    }

    @Override
    protected void op(ScriptContext context) {
        // Reset all tags and unlock specified tags
        context.instance.reset(tags);
    }
}
