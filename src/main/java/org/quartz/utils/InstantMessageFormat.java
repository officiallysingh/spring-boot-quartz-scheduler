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
package org.quartz.utils;

import java.text.FieldPosition;
import java.text.Format;
import java.text.MessageFormat;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAccessor;
import java.util.Locale;

/**
 * {@link MessageFormat} for templates whose date arguments are {@code java.time} values.
 *
 * <p>Templates keep the familiar {@code {n, date, HH:mm:ss MM/dd/yyyy}} / {@code {n, time}} syntax:
 * each date or time element is formatted with a {@link DateTimeFormatter} built from the same
 * pattern, in the system default time zone, so {@link java.time.Instant} (or any other temporal)
 * arguments can be passed (DECISIONS.md D-010).
 */
public final class InstantMessageFormat {

  private InstantMessageFormat() {}

  /**
   * Format a {@link java.text.MessageFormat} pattern whose date and time elements may take {@code
   * java.time} arguments.
   *
   * @param pattern the pattern, for example {@code "Job {1}.{0} fired at {2, date, HH:mm:ss}"}
   * @param arguments the arguments
   * @return the formatted message
   */
  public static String format(String pattern, Object... arguments) {
    MessageFormat messageFormat = new MessageFormat(pattern);
    Format[] formats = messageFormat.getFormats();
    for (int i = 0; i < formats.length; i++) {
      if (formats[i] instanceof SimpleDateFormat) {
        String datePattern = ((SimpleDateFormat) formats[i]).toPattern();
        messageFormat.setFormat(i, new TemporalFormat(datePattern, messageFormat.getLocale()));
      }
    }
    return messageFormat.format(arguments);
  }

  private static final class TemporalFormat extends Format {

    private static final long serialVersionUID = 1L;

    private final transient DateTimeFormatter formatter;

    TemporalFormat(String pattern, Locale locale) {
      this.formatter =
          DateTimeFormatter.ofPattern(pattern, locale).withZone(ZoneId.systemDefault());
    }

    @Override
    public StringBuffer format(Object obj, StringBuffer toAppendTo, FieldPosition pos) {
      if (obj instanceof TemporalAccessor) {
        return toAppendTo.append(formatter.format((TemporalAccessor) obj));
      }
      throw new IllegalArgumentException("Cannot format given Object as a date/time: " + obj);
    }

    @Override
    public Object parseObject(String source, ParsePosition pos) {
      throw new UnsupportedOperationException("parsing is not supported");
    }
  }
}
