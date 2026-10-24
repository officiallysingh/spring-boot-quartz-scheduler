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
import org.quartz.Job;
import org.quartz.JobDataMap;
import org.quartz.JobDetail;
import org.quartz.JobPersistenceException;
import org.quartz.impl.JobDetailImpl;

/**
 * Encodes job details as store-neutral field maps; job data is encoded separately by {@link
 * JobDataCodec}. The job class is stored by name and loaded through the scheduler's class loader.
 */
public final class JobDetailCodec {

  /** Stored field: the job's name. */
  public static final String NAME = "name";

  /** Stored field: the job's group. */
  public static final String GROUP = "group";

  /** Stored field: the job's description. */
  public static final String DESCRIPTION = "description";

  /** Stored field: the job class name. */
  public static final String JOB_CLASS = "jobClass";

  /** Stored field: the job's durable. */
  public static final String DURABLE = "durable";

  /** Stored field: the job's requests recovery. */
  public static final String REQUESTS_RECOVERY = "requestsRecovery";

  /** Stored field: whether the job disallows concurrent execution. */
  public static final String NON_CONCURRENT = "nonConcurrent";

  /** Stored field: whether the job data is stored again after each execution. */
  public static final String PERSIST_JOB_DATA = "persistJobData";

  private JobDetailCodec() {}

  /**
   * Encode a job, without its job data (encoded by {@link JobDataCodec}).
   *
   * @param job the job
   * @return the stored fields
   */
  public static Map<String, Object> encode(JobDetail job) {
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put(NAME, job.getKey().getName());
    fields.put(GROUP, job.getKey().getGroup());
    if (job.getDescription() != null) {
      fields.put(DESCRIPTION, job.getDescription());
    }
    fields.put(JOB_CLASS, job.getJobClass().getName());
    fields.put(DURABLE, job.isDurable());
    fields.put(REQUESTS_RECOVERY, job.requestsRecovery());
    fields.put(NON_CONCURRENT, job.isConcurrentExecutionDisallowed());
    fields.put(PERSIST_JOB_DATA, job.isPersistJobDataAfterExecution());
    return fields;
  }

  /**
   * Decode a job from its stored fields.
   *
   * @param fields the stored fields
   * @param jobData the decoded job data
   * @param classLoader loads the job class
   * @return the job
   * @throws JobPersistenceException if the job class cannot be loaded
   * @throws IllegalStateException if a required field is missing
   */
  public static JobDetail decode(Map<String, ?> fields, JobDataMap jobData, ClassLoader classLoader)
      throws JobPersistenceException {
    String className = Fields.requiredString(fields, JOB_CLASS);
    Class<? extends Job> jobClass;
    try {
      jobClass = Class.forName(className, false, classLoader).asSubclass(Job.class);
    } catch (ClassNotFoundException | ClassCastException ex) {
      throw new JobPersistenceException(
          "Couldn't load job class " + className + ": " + ex.getMessage(), ex);
    }
    JobDetailImpl job = new JobDetailImpl();
    job.setName(Fields.requiredString(fields, NAME));
    job.setGroup(Fields.string(fields, GROUP));
    job.setDescription(Fields.string(fields, DESCRIPTION));
    job.setJobClass(jobClass);
    job.setDurability(Fields.booleanValue(fields, DURABLE));
    job.setRequestsRecovery(Fields.booleanValue(fields, REQUESTS_RECOVERY));
    job.setJobDataMap((jobData != null) ? jobData : new JobDataMap());
    return job;
  }
}
