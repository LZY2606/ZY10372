package com.google.common.flogger;

import static com.google.common.flogger.LazyArgs.lazy;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.common.flogger.backend.LogData;
import com.google.common.flogger.backend.LoggerBackend;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Traces a single {@code FluentLogger.atInfo()} statement through the logging pipeline and pins
 * down, at each layer, which object identities must be preserved and how many times user code
 * (lazy arguments) is evaluated.
 *
 * <p>The predictions under test (derived from {@code FluentLogger}, {@code LogContext}, {@code
 * AbstractLogger} and the rate limiter classes):
 *
 * <ul>
 *   <li>A disabled level returns the {@code NO_OP} singleton; no {@code LogContext} is created and
 *       lazy arguments are never evaluated.
 *   <li>The {@code LogData} the backend receives is the very {@code LogContext} instance created
 *       by {@code FluentLogger.at()} ({@code logImpl()} calls {@code getLogger().write(this)}).
 *   <li>The {@code LogSite} is inferred in {@code shouldLog()} before any backend code runs, so it
 *       identifies the user's {@code log()} call, never the backend's own frames.
 *   <li>{@code LazyArg}s are evaluated at most once, inside {@code logImpl()}, before the backend
 *       is invoked; an exception there propagates to the caller and the backend sees nothing.
 *   <li>Rate limiter state is keyed by {@code LogSiteKey} equality (class/method/line, plus any
 *       {@code per()} qualifier), so two statements on the same source line share state while
 *       statements on different lines - or with different {@code per()} keys - do not.
 * </ul>
 */
@RunWith(JUnit4.class)
public class LogPipelineTraceTest {

  /**
   * Minimal backend which records, per layer, object identity (the exact {@code LogData}
   * references it receives) and call counts (how often {@code isLoggable} was consulted and how
   * many statements reached {@code log()}).
   */
  private static final class TracingBackend extends LoggerBackend {
    private Level minLevel = Level.ALL;
    int isLoggableCalls = 0;
    final List<LogData> logged = new ArrayList<>();
    final List<RuntimeException> errors = new ArrayList<>();

    void setLevel(Level level) {
      minLevel = level;
    }

    @Override
    public String getLoggerName() {
      return "com.example.Trace";
    }

    @Override
    public boolean isLoggable(Level level) {
      isLoggableCalls++;
      return level.intValue() >= minLevel.intValue();
    }

    @Override
    public void log(LogData data) {
      logged.add(data);
    }

    @Override
    public void handleError(RuntimeException error, LogData badData) {
      errors.add(error);
      throw error;
    }
  }

  private enum Bucket {
    ONE,
    TWO
  }

  private final TracingBackend backend = new TracingBackend();
  private final FluentLogger logger = new FluentLogger(backend);

  // Suppressed because capturing the Api object is the point of this identity check.
  @SuppressWarnings("FloggerSplitLogStatement")
  @Test
  public void disabledLevel_noOpFastPath_nothingEvaluated() {
    backend.setLevel(Level.WARNING);
    AtomicInteger evaluations = new AtomicInteger();

    FluentLogger.Api api = logger.atInfo();
    // Fast path: the singleton NO_OP is returned, no LogContext is ever allocated.
    assertThat(api).isSameInstanceAs(FluentLogger.NO_OP);
    api.withCause(new RuntimeException("ignored"))
        .log("value=%s", lazy(() -> {
          evaluations.incrementAndGet();
          return "unreachable";
        }));

    // The backend was consulted exactly once (isLoggable) and received no LogData; the lazy
    // argument was never evaluated because no LogContext/logImpl() was ever reached.
    assertThat(backend.isLoggableCalls).isEqualTo(1);
    assertThat(backend.logged).isEmpty();
    assertThat(evaluations.get()).isEqualTo(0);
  }

  // Suppressed because capturing the Api object is the point of this identity check.
  @SuppressWarnings("FloggerSplitLogStatement")
  @Test
  public void enabledLevel_backendReceivesTheSameContextInstance() {
    FluentLogger.Api api = logger.atInfo();
    assertThat(api).isNotSameInstanceAs(FluentLogger.NO_OP);
    api.log("hello");

    // Object identity across layers: the LogData seen by the backend IS the LogContext created
    // by FluentLogger.at() (LogContext implements LogData; logImpl() calls getLogger().write(this)).
    assertThat(backend.logged).hasSize(1);
    assertThat(backend.logged.get(0)).isSameInstanceAs(api);
    assertThat(backend.logged.get(0).getLiteralArgument()).isEqualTo("hello");
  }

  @Test
  public void logSite_identifiesUserCallSiteNotBackend() {
    // The log() call must stay exactly one line below this statement.
    int expectedLine = new Throwable().getStackTrace()[0].getLineNumber() + 1;
    logger.atInfo().log("site check");

    LogData data = backend.logged.get(0);
    // The log site is inferred in LogContext.shouldLog() (frontend), before the backend runs, so
    // it must name this test method - never a frame inside the logging backend itself.
    assertThat(data.getLogSite().getClassName()).isEqualTo(LogPipelineTraceTest.class.getName());
    assertThat(data.getLogSite().getMethodName()).isEqualTo("logSite_identifiesUserCallSiteNotBackend");
    assertThat(data.getLogSite().getLineNumber()).isEqualTo(expectedLine);
    assertThat(data.getLogSite().getClassName()).isNotEqualTo(TracingBackend.class.getName());
  }

  @Test
  public void lazyArg_evaluatedOnceBeforeBackendHandoff() {
    AtomicInteger evaluations = new AtomicInteger();

    logger.atInfo().log("value=%s", lazy(() -> {
      evaluations.incrementAndGet();
      return "computed";
    }));

    // Evaluated exactly once, inside logImpl(), before the backend is invoked; the backend
    // receives the evaluated value in place of the LazyArg instance.
    assertThat(evaluations.get()).isEqualTo(1);
    LogData data = backend.logged.get(0);
    assertThat(data.getTemplateContext().getMessage()).isEqualTo("value=%s");
    assertThat(data.getArguments()).hasLength(1);
    assertThat(data.getArguments()[0]).isEqualTo("computed");
  }

  @Test
  public void lazyArgThrowing_propagatesToCaller_backendSeesNothing() {
    RuntimeException boom = new RuntimeException("boom");
    AtomicInteger evaluations = new AtomicInteger();

    RuntimeException thrown =
        assertThrows(
            RuntimeException.class,
            () -> logger.atInfo().log("value=%s", lazy(() -> {
              evaluations.incrementAndGet();
              throw boom;
            })));

    // The exception escapes log() directly (it is thrown in logImpl() before write() is reached),
    // so the backend receives no LogData and handleError() is not involved.
    assertThat(thrown).isSameInstanceAs(boom);
    assertThat(evaluations.get()).isEqualTo(1);
    assertThat(backend.logged).isEmpty();
    assertThat(backend.errors).isEmpty();

    // State after the exception: the failed context is abandoned and the pipeline is intact;
    // re-running a fresh statement logs normally and the lazy arg is not retried.
    logger.atInfo().log("after");
    assertThat(backend.logged).hasSize(1);
    assertThat(evaluations.get()).isEqualTo(1);
  }

  @Test
  public void disabledVsLazyFailure_sameChainShape_compared() {
    RuntimeException cause = new RuntimeException("cause");

    // (a) Disabled level: the whole chain is a no-op; zero evaluations, zero LogData.
    backend.setLevel(Level.WARNING);
    AtomicInteger disabledEvals = new AtomicInteger();
    logger.atInfo()
        .withCause(cause)
        .log("v=%s", lazy(() -> {
          disabledEvals.incrementAndGet();
          return "x";
        }));
    assertThat(disabledEvals.get()).isEqualTo(0);
    assertThat(backend.logged).isEmpty();

    // (b) Enabled level with a throwing lazy arg: exactly one evaluation, still zero LogData.
    backend.setLevel(Level.INFO);
    AtomicInteger failingEvals = new AtomicInteger();
    RuntimeException boom = new RuntimeException("boom");
    assertThrows(
        RuntimeException.class,
        () -> logger.atInfo()
            .withCause(cause)
            .log("v=%s", lazy(() -> {
              failingEvals.incrementAndGet();
              throw boom;
            })));
    assertThat(failingEvals.get()).isEqualTo(1);
    assertThat(backend.logged).isEmpty();

    // (c) Enabled level, no failure: one evaluation, and the backend receives the cause metadata
    // (same Throwable instance) plus the evaluated argument.
    AtomicInteger okEvals = new AtomicInteger();
    logger.atInfo()
        .withCause(cause)
        .log("v=%s", lazy(() -> {
          okEvals.incrementAndGet();
          return "x";
        }));
    assertThat(okEvals.get()).isEqualTo(1);
    assertThat(backend.logged).hasSize(1);
    LogData data = backend.logged.get(0);
    assertThat(data.getMetadata().findValue(LogContext.Key.LOG_CAUSE)).isSameInstanceAs(cause);
    assertThat(data.getArguments()[0]).isEqualTo("x");
  }

  @Test
  public void recursiveLoggingFromLazyArg_innerStatementLoggedFirst() {
    AtomicInteger evaluations = new AtomicInteger();

    logger.atInfo().log("outer=%s", lazy(() -> {
      evaluations.incrementAndGet();
      logger.atInfo().log("inner");
      return "computed";
    }));

    // The inner statement is logged during evaluation of the outer statement's lazy argument,
    // i.e. before the outer statement itself reaches the backend. Each statement gets its own
    // LogContext/LogData instance, and recursion is permitted (depth well below the limit).
    assertThat(evaluations.get()).isEqualTo(1);
    assertThat(backend.logged).hasSize(2);
    assertThat(backend.logged.get(0).getLiteralArgument()).isEqualTo("inner");
    assertThat(backend.logged.get(1).getTemplateContext().getMessage()).isEqualTo("outer=%s");
    assertThat(backend.logged.get(1).getArguments()[0]).isEqualTo("computed");
    assertThat(backend.logged.get(1)).isNotSameInstanceAs(backend.logged.get(0));
  }

  @Test
  public void twoCallsOnSameLine_distinctInstancesButEqualLogSiteKeys() {
    // Do not reformat: both log statements must stay on the same physical line.
    logger.atInfo().log("first"); logger.atInfo().log("second");

    assertThat(backend.logged).hasSize(2);
    LogData first = backend.logged.get(0);
    LogData second = backend.logged.get(1);
    // Must be separate: each statement allocates its own LogContext and LogSite instances.
    assertThat(second).isNotSameInstanceAs(first);
    assertThat(second.getLogSite()).isNotSameInstanceAs(first.getLogSite());
    // Should be same: LogSite equality is (class, method, line), so same-line sites are equal
    // and act as the SAME key for rate limiting state.
    assertThat(second.getLogSite()).isEqualTo(first.getLogSite());
  }

  @Test
  public void twoCallsOnDifferentLines_unequalLogSiteKeys() {
    logger.atInfo().log("first");
    logger.atInfo().log("second");

    assertThat(backend.logged).hasSize(2);
    // Different lines produce different log site keys, hence independent rate limiting state.
    assertThat(backend.logged.get(1).getLogSite())
        .isNotEqualTo(backend.logged.get(0).getLogSite());
  }

  @Test
  public void rateLimitStateKey_sameLineSharesState_rerunReportsSkipped() {
    // Do not reformat: all three statements must stay on the same physical line. every(2) always
    // emits the first call, so A logs, B is skipped (shared counter) and C logs on the "rerun".
    logger.atInfo().every(2).log("A"); logger.atInfo().every(2).log("B"); logger.atInfo().every(2).log("C");

    assertThat(backend.logged).hasSize(2);
    assertThat(backend.logged.get(0).getLiteralArgument()).isEqualTo("A");
    LogData rerun = backend.logged.get(1);
    assertThat(rerun.getLiteralArgument()).isEqualTo("C");
    // The rerun of the same log site key reports the one skipped statement (B) as metadata.
    assertThat(rerun.getMetadata().findValue(LogContext.Key.SKIPPED_LOG_COUNT)).isEqualTo(1);
  }

  @Test
  public void rateLimitStateKey_differentLinesDoNotShareState() {
    logger.atInfo().every(2).log("C");
    logger.atInfo().every(2).log("D");

    // Separate lines => separate LogSiteMap entries => each statement's first call is emitted.
    assertThat(backend.logged).hasSize(2);
    assertThat(backend.logged.get(0).getLiteralArgument()).isEqualTo("C");
    assertThat(backend.logged.get(1).getLiteralArgument()).isEqualTo("D");
  }

  @Test
  public void rateLimitStateKey_perQualifierSeparatesStateOnSameLine() {
    // Do not reformat: both statements must stay on the same physical line. The per() qualifier
    // specializes the log site key, so the two statements must NOT share rate limiting state.
    logger.atInfo().every(2).per(Bucket.ONE).log("P1"); logger.atInfo().every(2).per(Bucket.TWO).log("P2");

    assertThat(backend.logged).hasSize(2);
    assertThat(backend.logged.get(0).getLiteralArgument()).isEqualTo("P1");
    assertThat(backend.logged.get(1).getLiteralArgument()).isEqualTo("P2");
  }
}
