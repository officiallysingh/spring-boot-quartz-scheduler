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
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import org.junit.jupiter.api.Test;

/**
 * Base class for unit tests that wish to verify backwards compatibility of serialization with
 * earlier versions of Quartz.
 *
 * <p>The way to properly setup tests for subclass is it needs to generate a <ClassName>.ser
 * resource file under the same package. This ".ser" file only needs to be generated one time, using
 * the version of Quartz matching to the VERSION values. Then during test, each of this file will be
 * deserialized to verify the data.
 */
public abstract class SerializationTestSupport {

  /**
   * Get the object to serialize when generating serialized file for future tests, and against which
   * to validate deserialized object.
   */
  protected abstract Object getTargetObject() throws Exception;

  /** Get the Quartz versions for which we should verify serialization backwards compatibility. */
  protected abstract String[] getVersions();

  /** Verify that the target object and the object we just deserialized match. */
  protected abstract void verifyMatch(Object target, Object deserialized);

  /**
   * Test that the target survives a serialization round trip, and that we can successfully
   * deserialize our target class for all of the given Quartz versions.
   *
   * <p>This method was not annotated with {@code @Test} after the JUnit 5 migration, so the
   * fixtures were never checked. Fixtures of the {@code java.util.Date} based classes can no longer
   * be read after the java.time migration; those tests list no versions and rely on the round trip
   * (DECISIONS.md D-010).
   */
  @Test
  void testSerialization() throws Exception {
    Object targetObject = getTargetObject();

    verifyMatch(targetObject, roundTrip(targetObject));

    for (int i = 0; i < getVersions().length; i++) {
      String version = getVersions()[i];

      verifyMatch(targetObject, deserialize(version, targetObject.getClass()));
    }
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

  /** Deserialize the target object from disk. */
  protected Object deserialize(String version, Class<?> clazz) throws Exception {
    InputStream is = getClass().getResourceAsStream(getSerializedFileName(version, clazz));

    ObjectInputStream ois = new ObjectInputStream(is);

    Object obj = (Object) ois.readObject();

    ois.close();
    is.close();

    return obj;
  }

  /** Use this method in the future to generate other versions of of the serialized object file. */
  public void writeJobDataFile(String version) throws Exception {
    Object obj = getTargetObject();

    FileOutputStream fos = new FileOutputStream(getSerializedFileName(version, obj.getClass()));

    ObjectOutputStream oos = new ObjectOutputStream(fos);

    oos.writeObject(obj);

    oos.flush();
    fos.close();
    oos.close();
  }

  /** Generate the expected name of the serialized object file. */
  private String getSerializedFileName(String version, Class<?> clazz) {
    String className = clazz.getName();
    int index = className.lastIndexOf(".");
    index = (index < 0) ? 0 : index + 1;

    return className.substring(index) + "-" + version + ".ser";
  }
}
