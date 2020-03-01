package sengine.sheets;

import com.badlogic.gdx.utils.Array;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

class Serializer<T> {

    public final Class<T> type;

    public final String[] methodNames;
    public final Method[] methods;
    public final Class<?>[][] methodParameterTypes;

    public final String[] fieldNames;
    public final Field[] fields;
    public final boolean[] fieldRequired;
    public final Class<?>[] fieldTypes;
    public final Primitive[] fieldPrimitives;
    public final Method[] fieldMethods;

    public Serializer(Class<T> type) {
        // Check if compatible constructor exist
//            try {                 // 20181117: removed as its possible to get an instantiated object supplied for parse()
//                type.getConstructor();
//            } catch (Throwable e) {
//                throw new MassException("Cannot parse objects without an accessible no-arg constructor: " + type);
//            }

        this.type = type;


        // Methods
        Array<Method> exposedMethods = new Array<>(Method.class);
        for(Method method : type.getMethods()) {
            if(method.getDeclaringClass() == Object.class)
                continue;
            exposedMethods.add(method);
        }
        methods = new Method[exposedMethods.size];
        methodNames = new String[exposedMethods.size];
        methodParameterTypes = new Class<?>[exposedMethods.size][];
        for(int c = 0; c < exposedMethods.size; c++) {
            Method method = exposedMethods.items[c];
            methods[c] = method;
            methodNames[c] = method.getName();
            methodParameterTypes[c] = method.getParameterTypes();
        }

        // Collect all fields
        SheetFields rowInfo = type.getAnnotation(SheetFields.class);
        if(rowInfo == null) {
            // No fields required
            fieldNames = new String[0];
            fields = new Field[0];
            fieldRequired = new boolean[0];
            fieldTypes = new Class<?>[0];
            fieldPrimitives = new Primitive[0];
            fieldMethods = new Method[0];
        }
        else {
            List<String> names = new ArrayList<>(Arrays.asList(rowInfo.fields()));
            List<String> requiredNames = new ArrayList<>(Arrays.asList(rowInfo.requiredFields()));

            // Combine names and required names
            fieldNames = Stream.concat(names.stream(), requiredNames.stream())
                    .distinct()
                    .toArray(String[]::new);

            fields = new Field[fieldNames.length];
            fieldRequired = new boolean[fieldNames.length];
            fieldTypes = new Class<?>[fieldNames.length];
            fieldPrimitives = new Primitive[fieldNames.length];
            fieldMethods = new Method[fieldNames.length];
            for(int c = 0; c < fieldNames.length; c++) {
                String fieldName = fieldNames[c];

                // Find a field method first
                int index = findMethod(fieldName);
                if(index != -1) {
                    // Its a field method
                    fieldMethods[c] = methods[index];
                    fieldTypes[c] = methodParameterTypes[index][0];
                    fieldPrimitives[c] = Primitive.get(fieldTypes[c]);
                }
                else {
                    // Else have to find a field with this name
                    try {
                        fields[c] = type.getField(fieldName);
                        fieldTypes[c] = fields[c].getType();
                        fieldPrimitives[c] = Primitive.get(fieldTypes[c]);
                    } catch (NoSuchFieldException e) {
                        throw new ParseException("Failed to retrieve field: " + fieldName);
                    }
                }

                // Check if required
                fieldRequired[c] = requiredNames.contains(fieldName);
            }
        }
    }

    public int findField(String name) {
        for(int c = 0; c < fieldNames.length; c++) {
            if(SheetParser.softStringEquals(fieldNames[c], name))
                return c;
        }
        return -1;
    }

    public int findMethod(String name) {
        for(int c = 0; c < methodNames.length; c++) {
            if(SheetParser.softStringEquals(methodNames[c], name))
                return c;
        }
        return -1;
    }

    public T newInstance () {
        try {
            return type.newInstance();
        } catch (Throwable e) {
            throw new ParseException("Unable to instantiate type: " + type, e);
        }
    }

    private static final ConcurrentHashMap<Class<?>, Serializer<?>> serializers = new ConcurrentHashMap<>();

    public static <T> Serializer<T> get(Class<T> type) {
        Serializer<T> serializer = (Serializer<T>) serializers.get(type);
        if(serializer == null) {
            // Create new
            serializer = new Serializer<>(type);
            serializers.put(type, serializer);
        }
        return serializer;
    }
}
