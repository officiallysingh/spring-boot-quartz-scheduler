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
import org.quartz.spi.ClassLoadHelper;

/**
 * Encodes job details as store-neutral field maps; job data is encoded separately by {@link
 * JobDataCodec}. The job class is stored by name and loaded through the scheduler's {@link
 * ClassLoadHelper}.
 */
public final class JobDetailCodec {

  public static final String NAME = "name";
  public static final String GROUP = "group";
  public static final String DESCRIPTION = "description";
  public static final String JOB_CLASS = "jobClass";
  public static final String DURABLE = "durable";
  public static final String REQUESTS_RECOVERY = "requestsRecovery";
  public static final String NON_CONCURRENT = "nonConcurrent";
  public static final String PERSIST_JOB_DATA = "persistJobData";

  private JobDetailCodec() {}

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

  public static JobDetail decode(
      Map<String, ?> fields, JobDataMap jobData, ClassLoadHelper loadHelper)
      throws JobPersistenceException {
    String className = Fields.requiredString(fields, JOB_CLASS);
    Class<? extends Job> jobClass;
    try {
      jobClass = loadHelper.loadClass(className, Job.class);
    } catch (ClassNotFoundException ex) {
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
