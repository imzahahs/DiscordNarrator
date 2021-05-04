package com.kaigan.bots.narrator.script;

import com.badlogic.gdx.utils.Array;
import sengine.sheets.ParseException;

public class ScriptBuilder {

    private final Array<Operation> ops = new Array<>(Operation.class);

    // Debug
    public void log(LogOp op) { add(op); }

    // Story
    public void addNpc(AddNpc op) { add(op); }
    public void removeNpc(RemoveNpc op) { add(op); }
    public void addPlayer(AddPlayer op) { add(op); }
    public void removePlayer(RemovePlayer op) { add(op); }
    public void ending(EndingOp op) { add(op); }
    public void eval(EvalOp op) { add(op); }
    public void reset(ResetOp op) { add(op); }

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
