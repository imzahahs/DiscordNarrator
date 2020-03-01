package sengine.sheets;

import com.badlogic.gdx.utils.Array;

public class SheetStack {

    private static final ThreadLocal<Array<Object>> stackLocal = new ThreadLocal<Array<Object>>() {
        @Override
        protected Array<Object> initialValue() {
            return new Array<>(Object.class);
        }
    };

    public static int size() {
        return stackLocal.get().size;
    }

    public static <T> T get(int index) {
        return (T) stackLocal.get().get(index);
    }

    public static <T> T first() {
        Array<Object> stack = stackLocal.get();
        if(stack.size > 0)
            return (T) stack.first();
        return null;
    }

    public static <T> T last() {
        Array<Object> stack = stackLocal.get();
        if(stack.size > 0)
            return (T) stack.peek();
        return null;
    }

    public static <T> T first(Class<T> type) {
        Array<Object> stack = stackLocal.get();
        for(Object o : stack) {
            if(type.isAssignableFrom(o.getClass()))
                return (T) o;
        }
        return null;
    }

    public static <T> T last(Class<T> type) {
        Array<Object> stack = stackLocal.get();
        for(int c = stack.size - 1; c >= 0; c--) {
            Object o = stack.items[c];
            if(type.isAssignableFrom(o.getClass()))
                return (T) o;
        }
        return null;
    }

    public static <T> T parentOf(Object o) {
        Array<Object> stack = stackLocal.get();
        int index = stack.indexOf(o, true) - 1;
        if(index >= 0)
            return (T) stack.items[index];
        return null;
    }

    public static <T> T parentOf(Object o, Class<T> type) {
        Array<Object> stack = stackLocal.get();
        int index = stack.indexOf(o, true) - 1;
        while(index >= 0) {
            Object parent = stack.items[index];
            if(type.isAssignableFrom(o.getClass()))
                return (T) parent;
            index--;
        }
        return null;        // not found
    }

    // Used by parser
    static void add(Object o) {
        stackLocal.get().add(o);
    }


    static void pop() {
        stackLocal.get().pop();
    }
}
