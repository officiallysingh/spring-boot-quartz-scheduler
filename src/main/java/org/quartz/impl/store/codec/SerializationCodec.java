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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.ObjectStreamClass;
import java.io.Serializable;
import java.util.Base64;
import org.quartz.JobPersistenceException;
import org.quartz.spi.ClassLoadHelper;

/**
 * Java serialization of values that have no structured encoding (calendars, custom triggers,
 * non-simple job data), resolving classes through the scheduler's {@link ClassLoadHelper}.
 */
public final class SerializationCodec {

  private SerializationCodec() {}

  /**
   * Java-serialize a value.
   *
   * @param value the value; must be {@link Serializable}
   * @return the bytes
   * @throws JobPersistenceException if it is not serializable or writing fails
   */
  public static byte[] serialize(Object value) throws JobPersistenceException {
    if (!(value instanceof Serializable)) {
      throw new JobPersistenceException(
          "Value of type " + value.getClass().getName() + " is not Serializable");
    }
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
      out.writeObject(value);
    } catch (IOException ex) {
      throw new JobPersistenceException(
          "Couldn't serialize " + value.getClass().getName() + ": " + ex.getMessage(), ex);
    }
    return bytes.toByteArray();
  }

  /**
   * Read a Java-serialized value, resolving its classes through the scheduler's class load helper.
   *
   * @param bytes the bytes
   * @param type the expected type
   * @param loadHelper loads the classes
   * @param <T> the expected type
   * @return the value
   * @throws JobPersistenceException if it cannot be read or is not of the expected type
   */
  public static <T> T deserialize(byte[] bytes, Class<T> type, ClassLoadHelper loadHelper)
      throws JobPersistenceException {
    try (ObjectInputStream in =
        new LoaderObjectInputStream(new ByteArrayInputStream(bytes), loadHelper)) {
      return type.cast(in.readObject());
    } catch (IOException | ClassNotFoundException | ClassCastException ex) {
      throw new JobPersistenceException(
          "Couldn't deserialize " + type.getSimpleName() + ": " + ex.getMessage(), ex);
    }
  }

  /**
   * Encode bytes as Base64, for stores that only keep strings.
   *
   * @param bytes the bytes
   * @return the Base64 text
   */
  public static String toBase64(byte[] bytes) {
    return Base64.getEncoder().encodeToString(bytes);
  }

  /**
   * Decode Base64 text.
   *
   * @param value the Base64 text
   * @return the bytes
   */
  public static byte[] fromBase64(String value) {
    return Base64.getDecoder().decode(value);
  }

  private static final class LoaderObjectInputStream extends ObjectInputStream {

    private final ClassLoadHelper loadHelper;

    LoaderObjectInputStream(InputStream in, ClassLoadHelper loadHelper) throws IOException {
      super(in);
      this.loadHelper = loadHelper;
    }

    @Override
    protected Class<?> resolveClass(ObjectStreamClass desc)
        throws IOException, ClassNotFoundException {
      if (loadHelper != null) {
        try {
          return Class.forName(desc.getName(), false, loadHelper.getClassLoader());
        } catch (ClassNotFoundException ex) {
          // fall back to the default resolution (primitives, bootstrap classes)
        }
      }
      return super.resolveClass(desc);
    }
  }
}
