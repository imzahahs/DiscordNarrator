package com.kaigan.bots.narrator.story;

import com.badlogic.gdx.utils.Array;
import sengine.mass.MassSerializable;

import java.util.HashMap;

/**
 * Created by Azmi on 19/7/2016.
 */
public class ScriptState implements MassSerializable {

    public interface OnChangeListener<T> {
        void onChanged(String name, T var, T prev);
    }

    private static class OnChangeListenerEntry {
        final String prefix;
        final Class type;
        final OnChangeListener listener;

        OnChangeListenerEntry(String prefix, Class type, OnChangeListener listener) {
            this.prefix = prefix;
            this.type = type;
            this.listener = listener;
        }
    }

    private final HashMap<String, Object> variables;
    private final Array<OnChangeListenerEntry> onChangeListeners = new Array<OnChangeListenerEntry>(OnChangeListenerEntry.class);

    public ScriptState() {
        variables = new HashMap<String, Object>();
    }

    @MassConstructor
    public ScriptState(HashMap<String, Object> variables) {
        this.variables = variables;
    }

    @Override
    public Object[] mass() {
        return new Object[] { variables };
    }

    public void clear() {
        variables.clear();
    }

    public <T> void addOnChangeListener(String prefix, Class<T> type, OnChangeListener<T> listener) {
        onChangeListeners.add(new OnChangeListenerEntry(prefix, type, listener));
    }

    public void removeOnChangeListener(OnChangeListener<?> listener) {
        removeOnChangeListener(null, listener);
    }

    public void removeOnChangeListener(String prefix, OnChangeListener<?> listener) {
        // Remove on change listener
        for(int c = 0; c < onChangeListeners.size; c++) {
            OnChangeListenerEntry e = onChangeListeners.items[c];
            if(e.listener != listener || (prefix != null && !e.prefix.equals(prefix)))
                continue;
            // Else can remove this entry
            onChangeListeners.removeIndex(c);
            c--;
        }
    }

    public void clearOnChangeListeners() {
        onChangeListeners.clear();
    }

    public <T> T get(String name, T defaultValue) {
        Object result = variables.get(name);
        if(result == null)
            return defaultValue;
        return (T) result;
    }

    public void set(String name, Object value) {
        set(name, value, true);         // notify on default
    }

    public void set(String name, Object value, boolean notifyOnChange) {
        Object stored = value;
        if(stored instanceof Boolean) {
            if(!((Boolean) stored))
                stored = null;
        }
        Object prev;
        if(stored == null)
            prev = variables.remove(name);
        else
            prev = variables.put(name, value);
        if(!notifyOnChange || prev == value || (prev != null && value != null && prev.equals(value)))
            return;     // nothing has changed or no need to notify
        // Check for on change
        for(int c = 0; c < onChangeListeners.size; c++) {
            OnChangeListenerEntry e = onChangeListeners.items[c];
            if(name.startsWith(e.prefix)) {
                // Name matches, check type
                Class<?> type = value != null ? value.getClass() : prev.getClass();
                if(e.type.isAssignableFrom(type)) {
                    e.listener.onChanged(name, value, prev);
                }
            }
        }
    }
}
