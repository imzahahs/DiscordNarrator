package sengine.sheets;

import java.lang.reflect.Field;

enum Primitive {
    BOOLEAN(boolean.class, Boolean.class) {
        @Override
        public void read(Field field, Object object, String value) throws IllegalArgumentException, IllegalAccessException {
            field.setBoolean(object, parseBoolean(value));
        }

        @Override
        public Object parse(Class<?> type, String value) {
            return parseBoolean(value);
        }

        @Override
        public Object defaultValue() {
            return false;
        }

        private boolean parseBoolean(String value) {
            value = value.toLowerCase();

            if(value.equals("true") || value.equals("yes"))
                return true;
            else if(value.equals("false") || value.equals("no"))
                return false;
            // Else unrecognized
            throw new ParseException("Unrecognized boolean value: " + value);
        }
    },
    BYTE(byte.class, Byte.class) {
        @Override
        public void read(Field field, Object object, String value) throws IllegalArgumentException, IllegalAccessException {
            field.setByte(object, Byte.parseByte(value));
        }

        @Override
        public Object parse(Class<?> type, String value) {
            return Byte.parseByte(value);
        }

        @Override
        public Object defaultValue() {
            return (byte)0;
        }
    },
    CHAR(char.class, Character.class) {
        @Override
        public void read(Field field, Object object, String value) throws IllegalArgumentException, IllegalAccessException {
            if(value.length() != 1)
                throw new ParseException("Invalid character: " + value);
            field.setChar(object, value.charAt(0));
        }

        @Override
        public Object parse(Class<?> type, String value) {
            if(value.length() != 1)
                throw new ParseException("Invalid character: " + value);
            return value.charAt(0);
        }

        @Override
        public Object defaultValue() {
            return (char)0;
        }
    },
    SHORT(short.class, Short.class) {
        @Override
        public void read(Field field, Object object, String value) throws IllegalArgumentException, IllegalAccessException {
            field.setShort(object, Short.parseShort(value));
        }

        @Override
        public Object parse(Class<?> type, String value) {
            return Short.parseShort(value);
        }

        @Override
        public Object defaultValue() {
            return (short)0;
        }
    },
    INT(int.class, Integer.class) {
        @Override
        public void read(Field field, Object object, String value) throws IllegalArgumentException, IllegalAccessException {
            field.setInt(object, Integer.parseInt(value));
        }

        @Override
        public Object parse(Class<?> type, String value) {
            return Integer.parseInt(value);
        }

        @Override
        public Object defaultValue() {
            return 0;
        }
    },
    LONG(long.class, Long.class) {
        @Override
        public void read(Field field, Object object, String value) throws IllegalArgumentException, IllegalAccessException {
            field.setLong(object, Long.parseLong(value));
        }

        @Override
        public Object parse(Class<?> type, String value) {
            return Long.parseLong(value);
        }

        @Override
        public Object defaultValue() {
            return 0L;
        }
    },
    FLOAT(float.class, Float.class) {
        @Override
        public void read(Field field, Object object, String value) throws IllegalArgumentException, IllegalAccessException {
            field.setFloat(object, Float.parseFloat(value));
        }

        @Override
        public Object parse(Class<?> type, String value) {
            return Float.parseFloat(value);
        }

        @Override
        public Object defaultValue() {
            return 0f;
        }
    },
    DOUBLE(double.class, Double.class) {
        @Override
        public void read(Field field, Object object, String value) throws IllegalArgumentException, IllegalAccessException {
            field.setDouble(object, Double.parseDouble(value));
        }

        @Override
        public Object parse(Class<?> type, String value) {
            return Double.parseDouble(value);
        }

        @Override
        public Object defaultValue() {
            return 0.0;
        }
    },
    STRING(String.class, String.class) {
        @Override
        public void read(Field field, Object object, String value) throws IllegalArgumentException, IllegalAccessException {
            field.set(object, value);
        }

        @Override
        public Object parse(Class<?> type, String value) {
            return value;
        }

        @Override
        public Object defaultValue() {
            return null;
        }
    },
    ENUM(null, null) {
        @Override
        public void read(Field field, Object object, String value) throws IllegalArgumentException, IllegalAccessException {
            field.set(object, parse(field.getType(), value));
        }

        @Override
        public Object parse(Class<?> type, String value) {
            Enum[] enums = (Enum[]) (type.isEnum() ? type.getEnumConstants() : type.getSuperclass().getEnumConstants());
            for (int e = 0; e < enums.length; e++) {
                if(SheetParser.softStringEquals(enums[e].name(), value)) {
                    return enums[e];
                }
            }

            throw new ParseException("Unknown enum value: " + value);
        }

        @Override
        public Object defaultValue() {
            return null;
        }
    }
    ;




    public final Class<?> primitiveType;
    public final Class<?> wrapperType;

    Primitive(Class<?> primitiveType, Class<?> wrapperType) {
        this.primitiveType = primitiveType;
        this.wrapperType = wrapperType;
    }

    public abstract void read(Field field, Object object, String value) throws IllegalArgumentException, IllegalAccessException;

    public abstract Object parse(Class<?> type, String value);

    public abstract Object defaultValue();

    public static Primitive get(Class<?> type) {
        Primitive[] primitives = values();
        for(int c = 0; c < primitives.length; c++) {
            Primitive p = primitives[c];
            if(type == p.primitiveType || type == p.wrapperType)
                return p;
        }
        // Check enum
        if(type.isEnum() || type.getSuperclass().isEnum())
            return ENUM;
        return null;
    }
}
