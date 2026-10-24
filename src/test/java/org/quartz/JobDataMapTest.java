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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Unit test for JobDataMap serialization. */
public class JobDataMapTest extends SerializationTestSupport {

  /** The object to serialize and compare with its deserialized copy. */
  @Override
  protected Object getTargetObject() {
    JobDataMap m = new JobDataMap();
    m.put("key", Integer.valueOf(5));
    return m;
  }

  /** Verify that the target object and the object we just deserialized match. */
  @Override
  protected void verifyMatch(Object target, Object deserialized) {
    JobDataMap targetMap = (JobDataMap) target;
    JobDataMap deserializedMap = (JobDataMap) deserialized;

    assertNotNull(deserializedMap);
    assertEquals(targetMap.getWrappedMap(), deserializedMap.getWrappedMap());
    assertEquals(targetMap.isDirty(), deserializedMap.isDirty());
  }
}
