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
package org.quartz.simpl;

import java.io.InputStream;
import java.net.URL;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.quartz.spi.ClassLoadHelper;

/**
 * Loads job classes and resources with the context class loader of the thread that initializes the
 * scheduler (the application's class loader, also in a Spring Boot fat jar), falling back to the
 * class loader of this library. The default of {@link org.quartz.impl.StdSchedulerFactory}; inside
 * an application context {@link org.quartz.spring.SchedulerFactoryBean} uses {@link
 * org.quartz.spring.ResourceLoaderClassLoadHelper} instead.
 */
public class DefaultClassLoadHelper implements ClassLoadHelper {

  private final ClassLoader libraryClassLoader =
      Objects.requireNonNull(DefaultClassLoadHelper.class.getClassLoader());

  private ClassLoader classLoader = libraryClassLoader;

  /** Captures the context class loader of the calling thread, if it has one. */
  @Override
  public void initialize() {
    ClassLoader context = Thread.currentThread().getContextClassLoader();
    classLoader = (context != null) ? context : libraryClassLoader;
  }

  @Override
  public Class<?> loadClass(String name) throws ClassNotFoundException {
    try {
      return classLoader.loadClass(name);
    } catch (ClassNotFoundException ex) {
      if (classLoader == libraryClassLoader) {
        throw ex;
      }
      return libraryClassLoader.loadClass(name);
    }
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> Class<? extends T> loadClass(String name, Class<T> clazz)
      throws ClassNotFoundException {
    return (Class<? extends T>) loadClass(name);
  }

  @Override
  public @Nullable URL getResource(String name) {
    URL resource = classLoader.getResource(name);
    return (resource != null) ? resource : libraryClassLoader.getResource(name);
  }

  @Override
  public @Nullable InputStream getResourceAsStream(String name) {
    InputStream resource = classLoader.getResourceAsStream(name);
    return (resource != null) ? resource : libraryClassLoader.getResourceAsStream(name);
  }

  @Override
  public ClassLoader getClassLoader() {
    return classLoader;
  }
}
