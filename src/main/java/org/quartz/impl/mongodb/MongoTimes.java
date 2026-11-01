/*
 * Copyright 2026 the spring-boot-quartz-scheduler authors.
 *
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
package org.quartz.impl.mongodb;

import java.time.Instant;
import java.util.Set;
import org.bson.BsonDateTime;
import org.jspecify.annotations.Nullable;
import org.quartz.impl.store.codec.TriggerCodec;

/**
 * How the MongoDB store keeps instants: BSON dates (millisecond precision), written as {@link
 * BsonDateTime} values so the format never depends on the client's codec registry (an application
 * registry may encode {@link Instant} differently).
 */
final class MongoTimes {

  /** The instant fields of trigger documents. */
  static final Set<String> TRIGGER_TIME_FIELDS =
      Set.of(
          TriggerCodec.NEXT_FIRE_TIME,
          TriggerCodec.PREVIOUS_FIRE_TIME,
          TriggerCodec.START_TIME,
          TriggerCodec.END_TIME);

  private MongoTimes() {}

  /** The stored form of an instant. */
  static BsonDateTime date(Instant instant) {
    return new BsonDateTime(instant.toEpochMilli());
  }

  /** The stored form of epoch milliseconds. */
  static BsonDateTime date(long epochMillis) {
    return new BsonDateTime(epochMillis);
  }

  /**
   * The instant a stored BSON date holds (decoded as {@code java.util.Date}).
   *
   * @throws IllegalArgumentException for any other value
   */
  static @Nullable Instant instant(@Nullable Object value) {
    return switch (value) {
      case null -> null;
      case java.util.Date date -> Instant.ofEpochMilli(date.getTime());
      case BsonDateTime date -> Instant.ofEpochMilli(date.getValue());
      default ->
          throw new IllegalArgumentException(
              "Not a stored instant: " + value + " (" + value.getClass().getName() + ")");
    };
  }

  /** Like {@link #instant(Object)}, for a value that must be present. */
  static Instant requiredInstant(@Nullable Object value, String field) {
    Instant instant = instant(value);
    if (instant == null) {
      throw new IllegalArgumentException("Missing stored instant '" + field + "'");
    }
    return instant;
  }
}
