package com.kaigan.bots.narrator.script;

import sengine.sheets.SheetFields;

@SheetFields(fields = { "js" }, requiredFields = { "js" })
public class EvalOp extends Operation {

    public String js;

    @Override
    protected void op(ScriptContext context) {
        context.instance.eval(js);
    }
}
