package org.quartz.spi;

import org.jspecify.annotations.Nullable;

/**
 * @author lorban
 */
public class TriggerFiredResult {

  private @Nullable TriggerFiredBundle triggerFiredBundle;

  private @Nullable Exception exception;

  public TriggerFiredResult(@Nullable TriggerFiredBundle triggerFiredBundle) {
    this.triggerFiredBundle = triggerFiredBundle;
  }

  public TriggerFiredResult(Exception exception) {
    this.exception = exception;
  }

  public @Nullable TriggerFiredBundle getTriggerFiredBundle() {
    return triggerFiredBundle;
  }

  public @Nullable Exception getException() {
    return exception;
  }
}
