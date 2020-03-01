package com.kaigan.bots.narrator.script;

import com.badlogic.gdx.utils.Array;

import sengine.sheets.ParseException;

public class ScriptBuilder {

    private final Array<Operation> ops = new Array<>(Operation.class);

    // Debug
    public void log(LogOp op) { add(op); }

    // Non-sheet functions
    private void add(Operation op) {
        if(op == null)
            throw new ParseException("missing operation parameters");
        ops.add(op);
    }

    public Operation[] build() {
        return ops.toArray();
    }


}
