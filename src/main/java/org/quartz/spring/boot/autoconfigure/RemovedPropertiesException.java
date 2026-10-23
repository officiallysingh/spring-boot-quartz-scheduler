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
package org.quartz.spring.boot.autoconfigure;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.context.properties.source.ConfigurationPropertyState;
import org.springframework.core.env.Environment;

/**
 * Thrown at startup when the environment still sets {@code spring.quartz.*} properties this library
 * no longer has. Binding would ignore them, silently changing behaviour (a different MongoDB
 * database, plugins no longer registered), so they fail fast instead. Reported by {@link
 * RemovedPropertiesFailureAnalyzer}.
 */
public class RemovedPropertiesException extends IllegalStateException {

  private static final String FIXED = "remove it: the setting keeps its default";

  /** Removed property (or property group) → what to do instead. */
  static final Map<String, String> REMOVED =
      Map.ofEntries(
          Map.entry(
              "spring.quartz.job-store.mongodb.database",
              "the store uses the application's MongoDB database: set 'spring.mongodb.database'"
                  + " (or the database of 'spring.mongodb.uri') to the database the jobs are in"),
          Map.entry(
              "spring.quartz.plugins",
              "define the plugins as beans (LoggingJobHistoryPlugin, LoggingTriggerHistoryPlugin,"
                  + " JobInterruptMonitorPlugin); the bean name is the plugin name"),
          Map.entry("spring.quartz.thread-pool.thread-priority", FIXED),
          Map.entry("spring.quartz.thread-pool.daemon", FIXED),
          Map.entry(
              "spring.quartz.thread-pool.threads-inherit-context-class-loader-of-initializing-thread",
              FIXED),
          Map.entry(
              "spring.quartz.thread-pool.threads-inherit-group-of-initializing-thread", FIXED),
          Map.entry("spring.quartz.scheduler.make-scheduler-thread-daemon", FIXED),
          Map.entry(
              "spring.quartz.scheduler.threads-inherit-context-class-loader-of-initializer", FIXED),
          Map.entry("spring.quartz.job-store.make-threads-daemons", FIXED),
          Map.entry(
              "spring.quartz.job-store.threads-inherit-initializers-class-load-context", FIXED),
          Map.entry("spring.quartz.job-store.double-check-lock-misfire-handler", FIXED),
          Map.entry("spring.quartz.job-store.acquire-triggers-within-lock", FIXED),
          Map.entry("spring.quartz.job-store.acquire-triggers-within-lock-retry", FIXED),
          Map.entry("spring.quartz.job-store.use-properties", FIXED));

  private final List<String> properties;

  private final List<String> actions;

  private RemovedPropertiesException(List<String> properties, List<String> actions) {
    super("Removed Quartz properties are set: " + String.join(", ", properties));
    this.properties = List.copyOf(properties);
    this.actions = List.copyOf(actions);
  }

  /** The removed properties (or groups) the environment sets. */
  public List<String> getProperties() {
    return this.properties;
  }

  /** For each of {@link #getProperties()}, what to do instead. */
  public List<String> getActions() {
    return this.actions;
  }

  /** Fails if the environment sets any of the removed properties. */
  static void check(Environment environment) {
    List<String> found = new ArrayList<>();
    List<String> actions = new ArrayList<>();
    REMOVED.keySet().stream()
        .sorted()
        .forEach(
            (name) -> {
              if (isSet(environment, ConfigurationPropertyName.of(name))) {
                found.add(name);
                actions.add(REMOVED.get(name));
              }
            });
    if (!found.isEmpty()) {
      throw new RemovedPropertiesException(found, actions);
    }
  }

  private static boolean isSet(Environment environment, ConfigurationPropertyName name) {
    for (ConfigurationPropertySource source : ConfigurationPropertySources.get(environment)) {
      if (source.getConfigurationProperty(name) != null
          || source.containsDescendantOf(name) == ConfigurationPropertyState.PRESENT) {
        return true;
      }
    }
    return false;
  }
}
