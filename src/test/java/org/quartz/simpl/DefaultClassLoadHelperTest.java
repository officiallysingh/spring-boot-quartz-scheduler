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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URL;
import java.net.URLClassLoader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.quartz.Job;

class DefaultClassLoadHelperTest {

  private final ClassLoader original = Thread.currentThread().getContextClassLoader();

  @AfterEach
  void restoreContextClassLoader() {
    Thread.currentThread().setContextClassLoader(original);
  }

  @Test
  void usesTheContextClassLoaderOfTheInitializingThread() throws Exception {
    URLClassLoader context = new URLClassLoader(new URL[0], original);
    Thread.currentThread().setContextClassLoader(context);
    DefaultClassLoadHelper helper = new DefaultClassLoadHelper();
    helper.initialize();
    Thread.currentThread().setContextClassLoader(original);

    assertThat(helper.getClassLoader()).isSameAs(context);
    assertThat(helper.loadClass(Job.class.getName(), Job.class)).isEqualTo(Job.class);
  }

  @Test
  void fallsBackToTheLibraryClassLoader() throws Exception {
    // a context class loader that sees nothing but the JDK
    Thread.currentThread().setContextClassLoader(new URLClassLoader(new URL[0], null));
    DefaultClassLoadHelper helper = new DefaultClassLoadHelper();
    helper.initialize();

    assertThat(helper.loadClass(Job.class.getName())).isEqualTo(Job.class);
    assertThat(helper.getResource("org/quartz/Job.class")).isNotNull();
    assertThatThrownBy(() -> helper.loadClass("org.quartz.NoSuchJob"))
        .isInstanceOf(ClassNotFoundException.class);
  }

  @Test
  void withoutAContextClassLoaderUsesTheLibraryClassLoader() throws Exception {
    Thread.currentThread().setContextClassLoader(null);
    DefaultClassLoadHelper helper = new DefaultClassLoadHelper();
    helper.initialize();

    assertThat(helper.getClassLoader()).isSameAs(DefaultClassLoadHelper.class.getClassLoader());
    assertThat(helper.loadClass(Job.class.getName())).isEqualTo(Job.class);
  }
}
