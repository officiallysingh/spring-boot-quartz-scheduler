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

import java.util.LinkedHashMap;
import java.util.Map;

import org.quartz.JobDataMap;
import org.quartz.JobPersistenceException;
import org.quartz.spi.ClassLoadHelper;

/**
 * Encodes {@link JobDataMap}s. A map whose values are all {@code String}, {@code Integer},
 * {@code Long}, {@code Double} or {@code Boolean} (and whose keys are plain field names) is
 * stored natively, so it stays readable in the store; any other map is stored
 * Java-serialized. With {@code useProperties} only {@code String} values are accepted,
 * as with the original JDBC store.
 */
public final class JobDataCodec {

    private JobDataCodec() {
    }

    /**
     * Fails if {@code useProperties} is set and a value is not a {@code String}.
     */
    public static void checkUseProperties(JobDataMap data, boolean useProperties) throws JobPersistenceException {
        if (!useProperties || data == null) {
            return;
        }
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            if (!(entry.getValue() instanceof String)) {
                throw new JobPersistenceException("JobDataMap values must be Strings when the 'useProperties' "
                        + "setting is enabled. Key of offending value: " + entry.getKey());
            }
        }
    }

    /** Whether the map can be stored natively (see class comment). */
    public static boolean isNative(JobDataMap data) {
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            String key = entry.getKey();
            if (key.isEmpty() || key.indexOf('.') >= 0 || key.startsWith("$")) {
                return false;
            }
            Object value = entry.getValue();
            if (!(value instanceof String || value instanceof Integer || value instanceof Long
                    || value instanceof Double || value instanceof Boolean)) {
                return false;
            }
        }
        return true;
    }

    /** Copies a natively stored map back into a clean {@link JobDataMap}. */
    public static JobDataMap fromNative(Map<String, ?> values) {
        JobDataMap data = new JobDataMap();
        if (values != null) {
            data.putAll(new LinkedHashMap<>(values));
        }
        data.clearDirtyFlag();
        return data;
    }

    public static byte[] serialize(JobDataMap data) throws JobPersistenceException {
        return SerializationCodec.serialize(data);
    }

    public static JobDataMap deserialize(byte[] bytes, ClassLoadHelper loadHelper) throws JobPersistenceException {
        JobDataMap data = SerializationCodec.deserialize(bytes, JobDataMap.class, loadHelper);
        data.clearDirtyFlag();
        return data;
    }

    /** A type-tagged string, for stores that only keep strings (Redis hashes). */
    public static String tag(Object value) {
        return switch (value) {
            case String s -> "s:" + s;
            case Integer i -> "i:" + i;
            case Long l -> "l:" + l;
            case Double d -> "d:" + d;
            case Boolean b -> "b:" + b;
            default -> throw new IllegalArgumentException("Not a native job data value: " + value.getClass());
        };
    }

    /** Reverses {@link #tag(Object)}. */
    public static Object untag(String tagged) {
        String value = tagged.substring(2);
        return switch (tagged.charAt(0)) {
            case 's' -> value;
            case 'i' -> Integer.valueOf(value);
            case 'l' -> Long.valueOf(value);
            case 'd' -> Double.valueOf(value);
            case 'b' -> Boolean.valueOf(value);
            default -> throw new IllegalArgumentException("Unknown job data tag: " + tagged);
        };
    }
}
