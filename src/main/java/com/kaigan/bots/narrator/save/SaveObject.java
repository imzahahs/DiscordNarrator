package com.kaigan.bots.narrator.save;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.Map;

public class SaveObject {
    private static final String CLASS_NAME_FIELD = "className";

    public static String toJson(Map<String, SaveObject> objects) {
        Gson gson = new GsonBuilder().setPrettyPrinting().registerTypeAdapterFactory(new RuntimeTypeAdapterFactory(SaveObject.class, CLASS_NAME_FIELD)).create();

        Type mapType = new TypeToken<Map<String, SaveObject>>() {}.getType();

        return gson.toJson(objects, mapType);
    }

    public static Map<String, SaveObject> fromJson(String json) {
        Gson gson = new GsonBuilder().setPrettyPrinting().registerTypeAdapterFactory(new RuntimeTypeAdapterFactory(SaveObject.class, CLASS_NAME_FIELD)).create();

        Type mapType = new TypeToken<Map<String, SaveObject>>() {}.getType();

        return gson.fromJson(json, mapType);
    }
}
