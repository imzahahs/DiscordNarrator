/*
 * Copyright (C) 2011 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.kaigan.bots.narrator.save;

import com.google.gson.*;
import com.google.gson.internal.Streams;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

class RuntimeTypeAdapterFactory implements TypeAdapterFactory {
    private final Class<?> baseType;
    private final String typeFieldName;

    public RuntimeTypeAdapterFactory(Class<?> baseType, String typeFieldName) {
        if (typeFieldName == null || baseType == null) {
            throw new NullPointerException();
        }
        this.baseType = baseType;
        this.typeFieldName = typeFieldName;
    }


    @Override
    public <R> TypeAdapter<R> create(Gson gson, TypeToken<R> type) {
        if (type.getRawType() != baseType) {
            return null;
        }

        final Map<String, TypeAdapter<?>> labelToDelegate = new LinkedHashMap<>();
        final Map<Class<?>, TypeAdapter<?>> subtypeToDelegate = new LinkedHashMap<>();

        return new TypeAdapter<R>() {
            @SuppressWarnings("unchecked")
            @Override
            public R read(JsonReader in) throws IOException {
                JsonElement jsonElement = Streams.parse(in);
                JsonElement labelJsonElement = jsonElement.getAsJsonObject().remove(typeFieldName);

                if (labelJsonElement == null) {
                    throw new JsonParseException("cannot deserialize " + baseType
                            + " because it does not define a field named " + typeFieldName);
                }
                String label = labelJsonElement.getAsString();
                TypeAdapter<R> delegate = (TypeAdapter<R>) labelToDelegate.get(label);
                if (delegate == null) {
                    try {
                        delegate = (TypeAdapter<R>) gson.getDelegateAdapter(RuntimeTypeAdapterFactory.this, TypeToken.get(Class.forName(label)));
                        labelToDelegate.put(label, delegate);
                    } catch (ClassNotFoundException e) {
                        throw new JsonParseException("Class not found for subtype named " + label, e);
                    }
                }
                return delegate.fromJsonTree(jsonElement);
            }

            @SuppressWarnings("unchecked") // registration requires that subtype extends T
            @Override public void write(JsonWriter out, R value) throws IOException {
                Class<?> srcType = value.getClass();
                String label = srcType.getName();
                TypeAdapter<R> delegate = (TypeAdapter<R>) subtypeToDelegate.get(srcType);
                if (delegate == null) {
                    delegate = (TypeAdapter<R>) gson.getDelegateAdapter(RuntimeTypeAdapterFactory.this, TypeToken.get(srcType));
                    subtypeToDelegate.put(srcType, delegate);
                }
                JsonObject jsonObject = delegate.toJsonTree(value).getAsJsonObject();
                JsonObject clone = new JsonObject();

                if (jsonObject.has(typeFieldName)) {
                    throw new JsonParseException("cannot serialize " + srcType.getName()
                            + " because it already defines a field named " + typeFieldName);
                }
                clone.add(typeFieldName, new JsonPrimitive(label));

                for (Map.Entry<String, JsonElement> e : jsonObject.entrySet()) {
                    clone.add(e.getKey(), e.getValue());
                }
                Streams.write(clone, out);
            }
        }.nullSafe();
    }
}