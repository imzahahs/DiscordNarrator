package com.kaigan.bots.narrator.script;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import sengine.sheets.SheetFields;

@SheetFields(fields = { "message" }, requiredFields = { "message" })
public class LogOp extends Operation {
    private static final Logger log = LogManager.getLogger("Operation");

    public String message;

    @Override
    protected void op(ScriptContext context) {
        log.info(message);
    }
}
