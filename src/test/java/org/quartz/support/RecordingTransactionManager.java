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
package org.quartz.support;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

/**
 * Resource-less transaction manager that records what happened, as {@code "begin <name>"}, {@code
 * "commit <name>"} and {@code "rollback <name>"} events.
 */
public class RecordingTransactionManager extends AbstractPlatformTransactionManager {

  private final List<String> events = new CopyOnWriteArrayList<>();

  private final List<TransactionDefinition> definitions = new CopyOnWriteArrayList<>();

  public List<String> events() {
    return events;
  }

  public List<TransactionDefinition> definitions() {
    return definitions;
  }

  @Override
  protected Object doGetTransaction() {
    return new Object();
  }

  @Override
  protected void doBegin(Object transaction, TransactionDefinition definition) {
    definitions.add(definition);
    events.add("begin " + definition.getName());
  }

  @Override
  protected void doCommit(DefaultTransactionStatus status) {
    events.add("commit " + status.getTransactionName());
  }

  @Override
  protected void doRollback(DefaultTransactionStatus status) {
    events.add("rollback " + status.getTransactionName());
  }
}
