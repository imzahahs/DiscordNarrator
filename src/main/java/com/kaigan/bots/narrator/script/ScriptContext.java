package com.kaigan.bots.narrator.script;

import com.badlogic.gdx.utils.Array;
import com.kaigan.bots.narrator.story.StoryChannelService;
import com.kaigan.bots.narrator.story.StoryInstanceService;
import com.kaigan.bots.narrator.story.StoryService;

public class ScriptContext implements Runnable {

    public final StoryChannelService channel;
    public final StoryInstanceService instance;
    public final StoryService service;

    public final Array<Operation> stack = new Array<>(Operation.class);
    public final Array<Operation> done = new Array<>(Operation.class);
    public boolean isWaiting = false;

    public ScriptContext insert(Operation ... ops) {
        for(int c = ops.length - 1; c >= 0; c--)
            stack.add(ops[c]);
        return this;
    }

    public ScriptContext add(Operation ... ops) {
        int size = stack.size;
        stack.setSize(size + ops.length);
        for(int c = 0; c < size; c++)
            stack.items[ops.length + c] = stack.items[c];
        for(int c = ops.length - 1, i = 0; c >= 0; c--, i++)
            stack.items[i] = ops[c];
        return this;
    }

    public ScriptContext rewind(Operation to) {
        Operation op;
        do {
            op = done.pop();
            stack.add(op);
        } while(op != to);
        return this;
    }

    public ScriptContext skip(Operation to) {
        while(stack.peek() != to)
            done.add(stack.pop());
        return this;
    }


    public ScriptContext(StoryChannelService channel, StoryInstanceService instance, StoryService service) {
        this.channel = channel;
        this.instance = instance;
        this.service = service;
    }

    public ScriptContext(ScriptContext copy) {
        this.channel = copy.channel;
        this.instance = copy.instance;
        this.service = copy.service;

        stack.addAll(copy.stack);
        done.addAll(copy.done);
        isWaiting = copy.isWaiting;
    }

    @Override
    public void run() {
        isWaiting = false;

        while(!isWaiting && !stack.isEmpty()) {
            Operation op = stack.pop();
            done.add(op);
            op.op(this);
        }
    }
}
