package com.kaigan.bots.narrator.story;

import com.kaigan.bots.narrator.NarratorBuilder;
import delight.nashornsandbox.NashornSandbox;
import delight.nashornsandbox.NashornSandboxes;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.script.ScriptException;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;

public class ScriptInterface {
    private static final Logger log = LogManager.getLogger("ScriptInterface");


    public class ScriptStateInterface implements Map<String, Object> {
        @Override
        public int size() {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isEmpty() {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean containsKey(Object key) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean containsValue(Object value) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Object get(Object key) {
            return instance.states.get((String)key, null);
        }

        @Nullable
        @Override
        public Object put(String key, Object value) {
            Object existing = get(key);
            instance.states.set(key, value);
            return existing;
        }

        @Override
        public Object remove(Object key) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void putAll(@NotNull Map<? extends String, ?> m) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void clear() {
            throw new UnsupportedOperationException();
        }

        @NotNull
        @Override
        public Set<String> keySet() {
            throw new UnsupportedOperationException();
        }

        @NotNull
        @Override
        public Collection<Object> values() {
            throw new UnsupportedOperationException();
        }

        @NotNull
        @Override
        public Set<Entry<String, Object>> entrySet() {
            throw new UnsupportedOperationException();
        }
    }


    private final StoryInstanceService instance;
    private final NashornSandbox scriptEngine;

    final ScheduledExecutorService scheduler = new ScheduledThreadPoolExecutor(1) {
        @Override
        protected void afterExecute(Runnable r, Throwable t) {
            super.afterExecute(r, t);
            if (t == null && r instanceof Future<?>) {
                Future<?> future = (Future<?>) r;
                if(future.isCancelled() || !future.isDone())
                    return;     // tasks are cancelled or not done, not sure why it wouldnt be done here, just following official javadoc sample
                // Else check exception
                try {
                    // Extract exception from future if exists
                    ((Future<?>) r).get();
                } catch (Throwable e) {
                    t = e;
                }
            }
            if (t != null)
                log.error("Exception in scheduler", t);
        }
    };

    ScriptInterface(StoryInstanceService instance) {
        this.instance = instance;

        // Create sandbox
        scriptEngine = NashornSandboxes.create();

        scriptEngine.setMaxCPUTime(10 * 1000);      // 10 seconds
        scriptEngine.setMaxMemory(10 * 1024 * 1024);        // 10 mb
        scriptEngine.allowNoBraces(false);
        scriptEngine.setExecutor(scheduler);

        scriptEngine.inject("states", new ScriptStateInterface());
        scriptEngine.inject("story", this);
    }

    // Advanced scripting functions
    public void eval(String js) {
        try {
            scriptEngine.eval(js);
        } catch (ScriptException e) {
            if(e.getMessage() != null)
                log.error("Failed to eval script:\n{}\n\nBecause:{}", js, e.getMessage());
            else
                log.error("Failed to eval script:\n{}\n\nBecause:{}", js, e);
        }
    }

    public void log(String text) {
        log.info(text);
    }

    public void after(String duration, Runnable function) {
        long millis = NarratorBuilder.parseDuration(duration);
        scheduler.schedule(function, millis, TimeUnit.MILLISECONDS);
    }

    public String getKeyboardReply(String channel) {
        return instance.channels.get(channel).keyboardReply;
    }

    public void setOnReceivePlayerMessage(String channel, StoryChannelService.OnReceivePlayerMessage onReceivePlayerMessage) {
        instance.channels.get(channel).onReceivePlayerMessage = onReceivePlayerMessage;
    }
}
