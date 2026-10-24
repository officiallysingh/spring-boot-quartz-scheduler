/*
 * All content copyright Terracotta, Inc., unless otherwise indicated. All rights reserved.
 * Copyright IBM Corp. 2024, 2025
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
package org.quartz;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import org.junit.jupiter.api.Test;

/**
 * Base class for unit tests of {@code Serializable} classes: the target must survive a Java
 * serialization round trip. Job data and trigger types other than the built-in ones are stored
 * Java-serialized (see {@code JobDataCodec} and {@code TriggerCodec}).
 */
public abstract class SerializationTestSupport {

  /** The object to serialize and compare with its deserialized copy. */
  protected abstract Object getTargetObject() throws Exception;

  /** Verify that the target object and its deserialized copy match. */
  protected abstract void verifyMatch(Object target, Object deserialized);

  @Test
  void testSerialization() throws Exception {
    Object targetObject = getTargetObject();
    verifyMatch(targetObject, roundTrip(targetObject));
  }

  private static Object roundTrip(Object target) throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ObjectOutputStream oos = new ObjectOutputStream(bytes)) {
      oos.writeObject(target);
    }
    try (ObjectInputStream ois =
        new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
      return ois.readObject();
    }
  }
}
