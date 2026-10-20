/*
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy
 * of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package org.quartz.impl.store.codec;

import java.util.Map;

/**
 * Typed reads from a store-neutral field map, whose values are either typed
 * ({@code String}, {@code Long}, {@code Integer}, {@code Boolean}, {@code byte[]}, as written
 * by MongoDB) or strings (as written to a Redis hash).
 */
final class Fields {

    private Fields() {
    }

    static String string(Map<String, ?> fields, String name) {
        Object value = fields.get(name);
        return (value != null) ? value.toString() : null;
    }

    static Long longValue(Map<String, ?> fields, String name) {
        Object value = fields.get(name);
        if (value == null) {
            return null;
        }
        return (value instanceof Number number) ? number.longValue() : Long.valueOf(value.toString());
    }

    static int intValue(Map<String, ?> fields, String name, int defaultValue) {
        Object value = fields.get(name);
        if (value == null) {
            return defaultValue;
        }
        return (value instanceof Number number) ? number.intValue() : Integer.parseInt(value.toString());
    }

    static boolean booleanValue(Map<String, ?> fields, String name) {
        Object value = fields.get(name);
        return (value instanceof Boolean bool) ? bool : value != null && Boolean.parseBoolean(value.toString());
    }

    static byte[] bytes(Map<String, ?> fields, String name) {
        Object value = fields.get(name);
        if (value == null) {
            return null;
        }
        return (value instanceof byte[] bytes) ? bytes : SerializationCodec.fromBase64(value.toString());
    }
}
