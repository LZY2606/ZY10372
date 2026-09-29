/*
 * Copyright (C) 2026 The Flogger Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.common.flogger;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static java.util.logging.Level.FINE;
import static java.util.logging.Level.INFO;
import static org.junit.Assert.fail;

import com.google.common.flogger.LogContext.Key;
import com.google.common.flogger.backend.LogData;
import com.google.common.flogger.backend.LoggerBackend;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Traces a single {@code FluentLogger.atInfo()} call chain end to end, asserting at which stage
 * each observable effect happens: API selection, lazy argument evaluation, metadata accumulation,
 * log site identity and hand-off to the backend.
 *
 * <p>Every assertion is labelled with a {@code stage=} and {@code input=} tag so that a regression
 * points directly at the pipeline stage and the minimal input which triggered it.
 */
@RunWith(JUnit4.class)
public class LogPipelineTraceTest {

  /**
   * Minimal backend which records the identity of every {@link LogData} it receives, in arrival
   * order. It never formats anything, so any argument evaluation observed by the test must have
   * happened in the fluent API layer (before {@code AbstractLogger.write}).
   */
  private static final class RecordingBackend extends LoggerBackend {
    final List<LogData> logged = new ArrayList<>();
    private Level minLevel = INFO;

    void setLevel(Level level) {
      this.minLevel = level;
    }

    @Override
    public String getLoggerName() {
      return "com.google.common.flogger.LogPipelineTraceTest";
    }

    @Override
    public boolean isLoggable(Level level) {
      return level.intValue() >= minLevel.intValue();
    }

    @Override
    public void log(LogData data) {
      logged.add(data);
    }

    @Override
    public void handleError(RuntimeException error, LogData badData) {
      throw error;
    }
  }

  /** A lazy argument which counts its evaluations and can be told to fail or run a side effect. */
  private static final class CountingLazyArg implements LazyArg<String> {
    int evaluations = 0;
    RuntimeException failure = null;
    Runnable sideEffect = null;

    @Override
    public String evaluate() {
      evaluations++;
      if (failure != null) {
        throw failure;
      }
      if (sideEffect != null) {
        sideEffect.run();
      }
      return "lazy-value";
    }
  }

  private static FluentLogger newLogger(RecordingBackend backend) {
    return new FluentLogger(backend);
  }

  // Splitting the fluent chain is deliberate: the test asserts object identity
  // between the Api returned by atInfo() and the LogData seen by the backend.
  @Test
  @SuppressWarnings("FloggerSplitLogStatement")
  public void disabledLevel_noOpFastPath_lazyArgNeverEvaluated() {
    RecordingBackend backend = new RecordingBackend();
    backend.setLevel(INFO);
    FluentLogger logger = newLogger(backend);
    CountingLazyArg lazy = new CountingLazyArg();
    RuntimeException cause = new RuntimeException("cause-disabled");

    FluentLogger.Api api = logger.atFine();
    assertWithMessage("stage=at(Level) input=disabled-fine: disabled level must return the"
            + " singleton no-op API, not a LogContext")
        .that(api)
        .isSameInstanceAs(FluentLogger.NO_OP);

    // The full fluent chain is silently swallowed by the no-op API.
    api.withCause(cause).every(2).log("hidden %s", LazyArgs.lazy(lazy));

    assertWithMessage("stage=logImpl input=disabled-fine: lazy arg must not be evaluated")
        .that(lazy.evaluations)
        .isEqualTo(0);
    assertWithMessage("stage=backend.log input=disabled-fine: backend must see nothing")
        .that(backend.logged)
        .isEmpty();

    // Same chain at an enabled level for contrast: exactly one evaluation, one LogData.
    logger.atInfo().withCause(cause).every(2).log("shown %s", LazyArgs.lazy(lazy));
    assertWithMessage("stage=logImpl input=enabled-info: lazy arg evaluated exactly once")
        .that(lazy.evaluations)
        .isEqualTo(1);
    assertWithMessage("stage=backend.log input=enabled-info: backend sees exactly one LogData")
        .that(backend.logged)
        .hasSize(1);
  }

  // Splitting the fluent chain is deliberate: the test asserts object identity
  // between the Api returned by atInfo() and the LogData seen by the backend.
  @Test
  @SuppressWarnings("FloggerSplitLogStatement")
  public void lazyArgThrows_exceptionPropagatesFromLogCall_backendSeesNothing() {
    RecordingBackend backend = new RecordingBackend();
    FluentLogger logger = newLogger(backend);
    CountingLazyArg lazy = new CountingLazyArg();
    RuntimeException boom = new RuntimeException("lazy-eval-failure");
    lazy.failure = boom;

    FluentLogger.Api api = logger.atInfo();
    try {
      api.log("value=%s", LazyArgs.lazy(lazy));
      fail("expected the lazy argument failure to propagate out of log()");
    } catch (RuntimeException e) {
      assertWithMessage(
              "stage=logImpl input=throwing-lazy-arg: the exact user exception must propagate"
                  + " (it is thrown by LazyArg.evaluate() inside LogContext.logImpl)")
          .that(e)
          .isSameInstanceAs(boom);
    }
    assertWithMessage(
            "stage=logImpl input=throwing-lazy-arg: evaluation happened once, after shouldLog()"
                + " selected a log site and before the backend was invoked")
        .that(lazy.evaluations)
        .isEqualTo(1);
    assertWithMessage(
            "stage=backend.log input=throwing-lazy-arg: backend.log() was never reached, so no"
                + " LogData exists after the exception")
        .that(backend.logged)
        .isEmpty();

    // Same throwing argument through the disabled fast path: not evaluated, no exception.
    backend.setLevel(INFO);
    logger.atFine().log("value=%s", LazyArgs.lazy(lazy));
    assertWithMessage("stage=logImpl input=disabled-throwing-lazy-arg: still only one evaluation")
        .that(lazy.evaluations)
        .isEqualTo(1);
    assertWithMessage("stage=backend.log input=disabled-throwing-lazy-arg: still no LogData")
        .that(backend.logged)
        .isEmpty();
  }

  // Splitting the fluent chain is deliberate: the test asserts object identity
  // between the Api returned by atInfo() and the LogData seen by the backend.
  @Test
  @SuppressWarnings("FloggerSplitLogStatement")
  public void withCause_contextIdentityAndMetadataReachBackend_logSiteIsApiCallSite() {
    RecordingBackend backend = new RecordingBackend();
    FluentLogger logger = newLogger(backend);
    RuntimeException cause = new RuntimeException("cause-metadata");

    FluentLogger.Api api = logger.atInfo().withCause(cause);
    api.log("with cause");

    assertWithMessage("stage=backend.log input=with-cause: exactly one LogData")
        .that(backend.logged)
        .hasSize(1);
    LogData data = backend.logged.get(0);
    assertWithMessage(
            "stage=backend.log input=with-cause: the LogData the backend receives IS the"
                + " LogContext created by atInfo() (same object across API/backend layers)")
        .that(data)
        .isSameInstanceAs(api);
    assertWithMessage("stage=backend.log input=with-cause: cause stored under LOG_CAUSE key")
        .that(data.getMetadata().findValue(Key.LOG_CAUSE))
        .isSameInstanceAs(cause);
    // The log site identifies the API call point (this test method), not LogContext.log() or
    // anything inside the backend.
    assertWithMessage("stage=shouldLog input=with-cause: log site class is the caller's class")
        .that(data.getLogSite().getClassName())
        .isEqualTo(LogPipelineTraceTest.class.getName());
    assertWithMessage("stage=shouldLog input=with-cause: log site method is the caller's method")
        .that(data.getLogSite().getMethodName())
        .isEqualTo("withCause_contextIdentityAndMetadataReachBackend_logSiteIsApiCallSite");
  }

  @Test
  public void twoCallsOnSameLine_equalButDistinctLogSites_sharedRateLimitState() {
    RecordingBackend backend = new RecordingBackend();
    FluentLogger logger = newLogger(backend);

    // Identity: two statements on the SAME source line produce two LogContexts whose log sites
    // are equal (same StackTraceElement) but not the same object.
    logger.atInfo().log("same-line A"); logger.atInfo().log("same-line B");
    assertWithMessage("stage=backend.log input=two-calls-same-line: both statements logged")
        .that(backend.logged)
        .hasSize(2);
    LogSite firstSite = backend.logged.get(0).getLogSite();
    LogSite secondSite = backend.logged.get(1).getLogSite();
    assertWithMessage("stage=shouldLog input=two-calls-same-line: distinct LogSite instances")
        .that(secondSite)
        .isNotSameInstanceAs(firstSite);
    assertWithMessage(
            "stage=shouldLog input=two-calls-same-line: equal keys (same class/method/line), so"
                + " rate limiting treats them as ONE log site")
        .that(secondSite)
        .isEqualTo(firstSite);
    assertWithMessage("stage=backend.log input=two-calls-same-line: distinct LogData instances")
        .that(backend.logged.get(1))
        .isNotSameInstanceAs(backend.logged.get(0));

    // State key: every(2) on ONE line shares a single CountingRateLimiter (keyed by the equal
    // LogSiteKey), so the first call logs and the second is skipped.
    RecordingBackend sharedBackend = new RecordingBackend();
    FluentLogger sharedLogger = newLogger(sharedBackend);
    sharedLogger.atInfo().every(2).log("shared-state"); sharedLogger.atInfo().every(2).log("shared-state");
    assertWithMessage(
            "stage=postProcess input=every(2)-same-line: one shared CountingRateLimiter state ->"
                + " 1st call allowed, 2nd skipped")
        .that(sharedBackend.logged)
        .hasSize(1);

    // Contrast: the same two statements on DIFFERENT lines get independent rate limiter states,
    // so each "first call" is allowed.
    RecordingBackend separateBackend = new RecordingBackend();
    FluentLogger separateLogger = newLogger(separateBackend);
    separateLogger.atInfo().every(2).log("separate-state");
    separateLogger.atInfo().every(2).log("separate-state");
    assertWithMessage(
            "stage=postProcess input=every(2)-different-lines: independent LogSiteKeys -> both"
                + " first calls allowed")
        .that(separateBackend.logged)
        .hasSize(2);
  }

  @Test
  public void lazyArgThatLogsRecursively_innerLogReachesBackendBeforeOuter() {
    RecordingBackend backend = new RecordingBackend();
    FluentLogger logger = newLogger(backend);
    CountingLazyArg lazy = new CountingLazyArg();
    lazy.sideEffect = () -> logger.atInfo().log("inner");

    logger.atInfo().log("outer %s", LazyArgs.lazy(lazy));

    assertWithMessage("stage=logImpl input=recursive-lazy-arg: outer lazy arg evaluated once")
        .that(lazy.evaluations)
        .isEqualTo(1);
    assertWithMessage("stage=backend.log input=recursive-lazy-arg: inner then outer")
        .that(backend.logged)
        .hasSize(2);
    // The inner statement is fully written (its own LogContext) while the outer statement is
    // still inside logImpl() evaluating arguments, so it arrives at the backend first.
    assertWithMessage(
            "stage=backend.log input=recursive-lazy-arg: recursive log arrives before the outer"
                + " statement which triggered it")
        .that(backend.logged.get(0).getLiteralArgument())
        .isEqualTo("inner");
    assertWithMessage("stage=backend.log input=recursive-lazy-arg: outer arg was evaluated")
        .that(backend.logged.get(1).getArguments())
        .asList()
        .containsExactly("lazy-value");
    assertWithMessage("stage=backend.log input=recursive-lazy-arg: distinct LogData instances")
        .that(backend.logged.get(1))
        .isNotSameInstanceAs(backend.logged.get(0));
  }
}
