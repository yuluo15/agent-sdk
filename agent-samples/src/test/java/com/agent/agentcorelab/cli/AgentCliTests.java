package com.agent.agentcorelab.cli;

import com.agent.agentcorelab.agent.AgentRunner;
import com.agent.agentcorelab.agent.AgentSpec;
import com.agent.agentcorelab.agent.CompletionCheck;
import com.agent.agentcorelab.agent.PendingQuestion;
import com.agent.agentcorelab.agent.RunLimits;
import com.agent.agentcorelab.agent.RunMetrics;
import com.agent.agentcorelab.agent.RunRequest;
import com.agent.agentcorelab.agent.RunResult;
import com.agent.agentcorelab.agent.RunSnapshot;
import com.agent.agentcorelab.agent.RunStatus;
import com.agent.agentcorelab.bootstrap.AgentProperties;
import com.agent.agentcorelab.model.ModelResponse;
import com.agent.agentcorelab.scenario.ScenarioPlugin;
import com.agent.agentcorelab.scenario.ScenarioRegistry;
import com.agent.agentcorelab.tool.Tool;
import com.agent.agentcorelab.tool.ToolCall;
import com.agent.agentcorelab.tool.ToolOutcome;
import com.agent.agentcorelab.workspace.Artifact;
import com.agent.agentcorelab.workspace.Workspace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class AgentCliTests {
    @TempDir
    Path root;
    private final RunLimits limits = new RunLimits(12, 20, Duration.ofSeconds(30), Duration.ofMinutes(2));

    @Test
    void missingModelStillSupportsDiscoverySelectionAndHelp() {
        var output = new StringWriter();
        var cli = new AgentCli(registry(), properties(), null, "file-inspection", new StringReader("""
                /scenarios
                /scenario other
                inspect files
                /status
                /answer no
                /cancel
                /tool arbitrary {}
                /help
                /exit
                """), output);
        cli.run();
        String text = output.toString();
        assertTrue(text.contains("仅本地、内存状态、重启不恢复；外发模型须用获准虚构资料"));
        assertTrue(text.contains("当前场景：file-inspection"));
        assertTrue(text.contains("当前场景：other"));
        assertTrue(text.contains("requestClarification"));
        assertTrue(text.contains("maxModelRequests=12"));
        assertTrue(text.contains(properties().workspace().inputRoot().toString()));
        assertTrue(text.contains("agent.model.enabled=true"));
        assertTrue(text.contains("AgentRunner bean"));
        assertTrue(text.contains("未知命令"));
        assertFalse(text.contains("状态=COMPLETED"));
    }

    @Test
    void busyCancellationCannotReleaseSlotOrSwitchUntilOldWorkerReturns() {
        var runner = new FakeRunner();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var worker = Executors.newSingleThreadExecutor();
        var output = new StringWriter();
        runner.onRun = () -> {
            if (runner.runs.get() == 1) {
                entered.countDown();
                await(release);
                return runner.result();
            }
            return runner.finish(RunStatus.COMPLETED);
        };
        var input = new ScriptInput(
                () -> "first goal",
                () -> { await(entered); return "/status"; },
                () -> "/scenario other",
                () -> "another goal",
                () -> "/answer not waiting",
                () -> "/cancel",
                () -> {
                    assertEquals(RunStatus.CANCELLED, runner.current.status());
                    assertEquals(1, runner.cancels.get());
                    // The fake has published a terminal snapshot, but its run() is still blocked.
                    return "must not start";
                },
                () -> "/scenario other",
                () -> "/status",
                () -> {
                    assertEquals(1, runner.runs.get());
                    assertTrue(output.toString().contains("执行槽：占用（worker 尚未结束）；已请求取消"));
                    release.countDown();
                    drain(worker);
                    return "/scenario other";
                },
                () -> "second goal",
                () -> { drain(worker); return "/status"; },
                () -> "/exit");
        try {
            new AgentCli(registry(), properties(), runner, "file-inspection", input, output, worker).run();
        } finally {
            release.countDown();
            worker.shutdownNow();
        }
        assertEquals(2, runner.runs.get());
        assertEquals(0, runner.resumes.get());
        assertEquals(List.of("file-inspection", "other"), runner.specs.stream().map(AgentSpec::id).toList());
        assertEquals(List.of("first goal", "second goal"), runner.requests.stream().map(RunRequest::goal).toList());
        assertTrue(runner.requests.stream().allMatch(request -> request.workspace().equals(properties().workspace())));
        assertTrue(output.toString().contains("忙碌：任务或旧 worker 尚未结束"));
        assertTrue(output.toString().contains("状态=RUNNING"));
        assertTrue(runner.currentReads.get() > 0);
    }

    @Test
    void waitingRequiresExplicitAnswerAndResumesSameRunWithoutChangingPermissions() {
        var runner = new FakeRunner();
        var resumed = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var worker = Executors.newSingleThreadExecutor();
        var output = new StringWriter();
        var ids = new ArrayList<UUID>();
        runner.onRun = () -> runner.finish(RunStatus.WAITING_INPUT);
        runner.onResume = () -> {
            resumed.countDown();
            await(release);
            return runner.finish(RunStatus.COMPLETED);
        };
        var input = new ScriptInput(
                () -> "核对资料",
                () -> {
                    drain(worker);
                    ids.add(runner.current.runId());
                    assertTrue(output.toString().contains("1. 九月"));
                    assertTrue(output.toString().contains("2. 十月"));
                    return "/scenario other";
                },
                () -> "九月",
                () -> "/answer",
                () -> "/answer 九月，中文说明",
                () -> { await(resumed); return "/answer duplicate"; },
                () -> "/status",
                () -> { release.countDown(); drain(worker); return "/status"; },
                () -> "/exit");
        try {
            new AgentCli(registry(), properties(), runner, "file-inspection", input, output, worker).run();
        } finally {
            release.countDown();
            worker.shutdownNow();
        }
        assertEquals(1, runner.runs.get());
        assertEquals(1, runner.resumes.get());
        assertEquals(ids.getFirst(), runner.resumedId);
        assertEquals(ids.getFirst(), runner.current.runId());
        assertEquals("九月，中文说明", runner.answer);
        assertEquals("file-inspection", runner.specs.getFirst().id());
        assertTrue(output.toString().contains("WAITING_INPUT：只能 /answer 或 /cancel"));
        assertTrue(output.toString().contains("用法：/answer"));
        assertTrue(output.toString().contains("usage=unknown"));
        assertTrue(output.toString().contains("模型轮数=2/12 工具调用=1/20"));
    }

    @Test
    void cancellingWhileWaitingDoesNotResumeAndAllowsScenarioChange() {
        var runner = new FakeRunner();
        var worker = Executors.newSingleThreadExecutor();
        var output = new StringWriter();
        runner.onRun = () -> runner.finish(RunStatus.WAITING_INPUT);
        var input = new ScriptInput(
                () -> "goal",
                () -> { drain(worker); return "/cancel"; },
                () -> "/answer too late",
                () -> "/scenario other",
                () -> null);
        new AgentCli(registry(), properties(), runner, "file-inspection", input, output, worker).run();
        assertEquals(RunStatus.CANCELLED, runner.current.status());
        assertEquals(1, runner.cancels.get());
        assertEquals(0, runner.resumes.get());
        assertTrue(output.toString().contains("当前场景：other"));
        assertTrue(worker.isTerminated());
    }

    @Test
    void eofCancelsRunningTaskAndWaitsForActualWorkerCompletion() {
        var runner = new FakeRunner();
        var entered = new CountDownLatch(1);
        var worker = Executors.newSingleThreadExecutor();
        runner.onRun = () -> {
            entered.countDown();
            await(runner.cancelled);
            return runner.result();
        };
        var input = new ScriptInput(() -> "goal", () -> { await(entered); return null; });
        var output = new StringWriter();
        new AgentCli(registry(), properties(), runner, "file-inspection", input, output, worker).run();
        assertEquals(1, runner.cancels.get());
        assertEquals(RunStatus.CANCELLED, runner.current.status());
        assertTrue(worker.isTerminated());
        assertTrue(output.toString().contains("等待已提交的 worker 安全结束"));
    }

    @Test
    void eofWhileWaitingCancelsPendingQuestion() {
        var runner = new FakeRunner();
        var worker = Executors.newSingleThreadExecutor();
        runner.onRun = () -> runner.finish(RunStatus.WAITING_INPUT);
        var input = new ScriptInput(() -> "goal", () -> { drain(worker); return null; });
        new AgentCli(registry(), properties(), runner, "file-inspection", input, new StringWriter(), worker).run();
        assertEquals(1, runner.cancels.get());
        assertEquals(RunStatus.CANCELLED, runner.current.status());
        assertTrue(worker.isTerminated());
    }

    @Test
    void cancellationBeforeWorkerEntryNeverStartsRunner() {
        var runner = new FakeRunner();
        var worker = Executors.newSingleThreadExecutor();
        var blocked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        worker.submit(() -> { blocked.countDown(); await(release); });
        await(blocked);
        var input = new ScriptInput(() -> "goal", () -> "/cancel", () -> {
            release.countDown();
            drain(worker);
            return "/exit";
        });
        try {
            new AgentCli(registry(), properties(), runner, "file-inspection", input, new StringWriter(), worker).run();
        } finally {
            release.countDown();
            worker.shutdownNow();
        }
        assertEquals(0, runner.runs.get());
    }

    @Test
    void cancellationWithoutPublishedIdIsRetainedUntilSnapshotBecomesAvailable() {
        var runner = new FakeRunner();
        var worker = Executors.newSingleThreadExecutor();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var output = new StringWriter();
        runner.onRun = () -> {
            RunSnapshot starting = runner.current;
            runner.current = null;
            entered.countDown();
            await(release);
            runner.current = starting;
            return runner.finish(RunStatus.WAITING_INPUT);
        };
        var input = new ScriptInput(() -> "goal", () -> { await(entered); return "/cancel"; },
                () -> "must not start", () -> {
                    assertEquals(1, runner.runs.get());
                    release.countDown();
                    drain(worker);
                    return "/exit";
                });
        try {
            new AgentCli(registry(), properties(), runner, "file-inspection", input, output, worker).run();
        } finally {
            release.countDown();
            worker.shutdownNow();
        }
        assertEquals(1, runner.cancels.get());
        assertEquals(RunStatus.CANCELLED, runner.current.status());
        assertTrue(output.toString().contains("runId 尚未发布；取消请求会保留"));
    }

    @Test
    void lateWaitingResultCannotOverwriteCancellationSnapshot() {
        var runner = new FakeRunner();
        var worker = Executors.newSingleThreadExecutor();
        var entered = new CountDownLatch(1);
        var output = new StringWriter();
        runner.onRun = () -> {
            RunResult stale = runner.finish(RunStatus.WAITING_INPUT);
            entered.countDown();
            await(runner.cancelled);
            return stale;
        };
        var input = new ScriptInput(() -> "goal", () -> { await(entered); return "/cancel"; },
                () -> { drain(worker); return "/exit"; });
        new AgentCli(registry(), properties(), runner, "file-inspection", input, output, worker).run();
        assertTrue(output.toString().contains("状态=CANCELLED"));
        assertFalse(output.toString().contains("状态=WAITING_INPUT"));
    }

    @Test
    void resultUsesSnapshotBudgetsUsageRecentToolAndOnlySnapshotArtifactPaths() {
        var runner = new FakeRunner();
        var worker = Executors.newSingleThreadExecutor();
        var output = new StringWriter();
        Path realPath = root.resolve("output").resolve("actual-report.md");
        runner.onRun = () -> {
            runner.current = new RunSnapshot(runner.current.runId(), "file-inspection", "goal", RunStatus.COMPLETED,
                    List.of(new RunSnapshot.Observation(new ToolCall("tool-id", "verifiedTool", "{}"),
                            new ToolOutcome.Success("{}"))),
                    List.of(new Artifact("real-artifact", realPath, "digest", List.of("evidence"))),
                    new RunMetrics(4, 3, Duration.ofSeconds(7), Optional.of(new ModelResponse.TokenUsage(120, 35))),
                    Optional.empty());
            return new RunResult(runner.current, "Report saved at Z:/invented/report.md", "COMPLETED");
        };
        var input = new ScriptInput(() -> "goal", () -> { drain(worker); return "/status"; }, () -> "/exit");
        new AgentCli(registry(), properties(), runner, "file-inspection", input, output, worker).run();
        String text = output.toString();
        assertTrue(text.contains("模型轮数=4/12 工具调用=3/20 主动时间=PT7S/PT2M"));
        assertTrue(text.contains("usage=inputTokens=120 outputTokens=35"));
        assertTrue(text.contains("最近工具=verifiedTool"));
        assertTrue(text.contains("real-artifact -> " + realPath));
        assertTrue(text.contains("文本中的路径不是产物凭证"));
        assertFalse(text.substring(text.indexOf("真实产物（")).contains("Z:/invented/report.md"));
    }

    @Test
    void thrownErrorsAndFailedResultMessagesDoNotExposeCredentials() {
        var runner = new FakeRunner();
        var worker = Executors.newSingleThreadExecutor();
        var output = new StringWriter();
        runner.onRun = () -> {
            runner.finish(RunStatus.FAILED);
            if (runner.runs.get() == 1) {
                throw new IllegalStateException("Authorization: Bearer top-secret https://user:password@host");
            }
            return new RunResult(runner.current, "password=top-secret", "API_KEY=top-secret");
        };
        var input = new ScriptInput(
                () -> "first",
                () -> { drain(worker); return "second"; },
                () -> { drain(worker); return "/exit"; });
        new AgentCli(registry(), properties(), runner, "file-inspection", input, output, worker).run();
        assertFalse(output.toString().contains("top-secret"));
        assertFalse(output.toString().contains("password"));
        assertTrue(output.toString().contains("未显示底层异常"));
        assertTrue(output.toString().contains("状态=FAILED"));
        assertEquals(2, runner.runs.get());
    }

    @Test
    void invalidScenarioAndOversizedInputNeverCallRunner() {
        var runner = new FakeRunner();
        var output = new StringWriter();
        String script = "goal\n/scenario not-installed\n/scenario other\n" + "x".repeat(16001)
                + "\n/scenarios unexpected\n/exit unexpected\n/exit\n";
        new AgentCli(registry(), properties(), runner, "missing", new StringReader(script), output).run();
        assertEquals(0, runner.runs.get());
        assertTrue(output.toString().contains("当前场景无效"));
        assertTrue(output.toString().contains("目标必须为 1–16000 个字符"));
        assertTrue(output.toString().contains("该命令不接受参数"));
    }

    @Test
    void inputFailureIsSanitizedAndStopsSafely() {
        BufferedReader input = new BufferedReader(new StringReader("")) {
            @Override
            public String readLine() throws IOException {
                throw new IOException("credentials=secret-input");
            }
        };
        var output = new StringWriter();
        new AgentCli(registry(), properties(), null, "file-inspection", input, output).run();
        assertTrue(output.toString().contains("输入不可用"));
        assertFalse(output.toString().contains("secret-input"));
    }

    private AgentProperties properties() {
        return new AgentProperties(limits, new Workspace(root.resolve("input"), root.resolve("output"), root.resolve("traces")));
    }

    private ScenarioRegistry registry() {
        return new ScenarioRegistry(List.of(scenario("file-inspection"), scenario("other")), limits);
    }

    static ScenarioPlugin scenario(String id) {
        return new ScenarioPlugin() {
            @Override public String id() { return id; }
            @Override public String description() { return "本地测试场景 " + id; }
            @Override public String instructions() { return "只能使用已选工具和可信工作区。"; }
            @Override public List<Tool> tools() { return List.of(); }
            @Override public CompletionCheck completionCheck() {
                return (snapshot, answer) -> new CompletionCheck.Result(true, "");
            }
        };
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS), "latch timed out");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError(ex);
        }
    }

    private static void drain(ExecutorService worker) {
        try {
            worker.submit(() -> {}).get(5, TimeUnit.SECONDS);
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
    }

    private static final class ScriptInput extends BufferedReader {
        private final ArrayDeque<Supplier<String>> steps;

        @SafeVarargs
        private ScriptInput(Supplier<String>... steps) {
            super(new StringReader(""));
            this.steps = new ArrayDeque<>(Arrays.asList(steps));
        }

        @Override
        public String readLine() {
            return steps.isEmpty() ? null : steps.removeFirst().get();
        }
    }

    private static final class FakeRunner implements AgentRunner {
        private volatile RunSnapshot current;
        private final AtomicInteger runs = new AtomicInteger();
        private final AtomicInteger resumes = new AtomicInteger();
        private final AtomicInteger cancels = new AtomicInteger();
        private final AtomicInteger currentReads = new AtomicInteger();
        private final CountDownLatch cancelled = new CountDownLatch(1);
        private final List<AgentSpec> specs = new ArrayList<>();
        private final List<RunRequest> requests = new ArrayList<>();
        private UUID resumedId;
        private String answer;
        private Supplier<RunResult> onRun = () -> finish(RunStatus.COMPLETED);
        private Supplier<RunResult> onResume = () -> finish(RunStatus.COMPLETED);

        @Override
        public RunResult run(AgentSpec agent, RunRequest request) {
            runs.incrementAndGet();
            specs.add(agent);
            requests.add(request);
            current = new RunSnapshot(UUID.randomUUID(), agent.id(), request.goal(), RunStatus.RUNNING,
                    List.of(), List.of(), new RunMetrics(1, 0, Duration.ZERO, Optional.empty()), Optional.empty());
            return onRun.get();
        }

        @Override
        public RunResult resume(UUID runId, String answer) {
            assertEquals(current.runId(), runId);
            assertEquals(RunStatus.WAITING_INPUT, current.status());
            resumes.incrementAndGet();
            this.resumedId = runId;
            this.answer = answer;
            finish(RunStatus.RUNNING);
            return onResume.get();
        }

        @Override
        public void cancel(UUID runId) {
            assertEquals(current.runId(), runId);
            if (current.status() == RunStatus.RUNNING || current.status() == RunStatus.WAITING_INPUT) {
                cancels.incrementAndGet();
                finish(RunStatus.CANCELLED);
                cancelled.countDown();
            }
        }

        @Override
        public RunSnapshot snapshot(UUID runId) {
            assertEquals(current.runId(), runId);
            return current;
        }

        @Override
        public Optional<RunSnapshot> currentSnapshot() {
            currentReads.incrementAndGet();
            return Optional.ofNullable(current);
        }

        private RunResult finish(RunStatus status) {
            current = new RunSnapshot(current.runId(), current.agentId(), current.goal(), status, List.of(), List.of(),
                    new RunMetrics(2, 1, Duration.ofSeconds(2), Optional.empty()), status == RunStatus.WAITING_INPUT
                    ? Optional.of(new PendingQuestion("question-id", "请选择期间", List.of("九月", "十月")))
                    : Optional.empty());
            return result();
        }

        private RunResult result() {
            return new RunResult(current, "result", current.status().name());
        }
    }
}
