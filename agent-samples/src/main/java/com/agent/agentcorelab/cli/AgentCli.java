package com.agent.agentcorelab.cli;

import com.agent.agentcorelab.agent.AgentRunner;
import com.agent.agentcorelab.agent.AgentSpec;
import com.agent.agentcorelab.agent.run.RunLimits;
import com.agent.agentcorelab.agent.run.RunRequest;
import com.agent.agentcorelab.agent.run.RunResult;
import com.agent.agentcorelab.agent.run.RunSnapshot;
import com.agent.agentcorelab.agent.run.RunStatus;
import com.agent.agentcorelab.bootstrap.AgentProperties;
import com.agent.agentcorelab.scenario.ScenarioRegistry;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** One input controller and one serial execution slot; never cancels a Future to free that slot. */
public final class AgentCli implements Runnable, AutoCloseable {
    private static final String MODEL_MISSING = "模型配置缺失：请设置 agent.model.enabled=true 并配置模型及 AgentRunner bean；"
            + "当前不能执行任务，/scenarios 仍可用。";
    private final ScenarioRegistry scenarios;
    private final AgentProperties properties;
    private final AgentRunner runner;
    private final BufferedReader input;
    private final PrintWriter output;
    private final ExecutorService worker;
    private final Object control = new Object();
    private ScenarioRegistry.Entry selected;
    private AgentSpec activeSpec;
    private Future<?> inFlight;
    private RunSnapshot latest;
    private String stopReason = "";
    private UUID previousRunId;
    private UUID runId;
    private volatile boolean cancelRequested;
    private boolean cancelDelivered;
    private volatile boolean closed;

    /** A null runner enables configuration help and scenario discovery without pretending to execute. */
    public AgentCli(ScenarioRegistry scenarios, AgentProperties properties, AgentRunner runner,
                    String initialScenario, Reader input, Writer output) {
        this(scenarios, properties, runner, initialScenario, input, output,
                Executors.newSingleThreadExecutor(action -> new Thread(action, "agent-cli-worker")));
    }

    AgentCli(ScenarioRegistry scenarios, AgentProperties properties, AgentRunner runner,
             String initialScenario, Reader input, Writer output, ExecutorService worker) {
        this.scenarios = Objects.requireNonNull(scenarios);
        this.properties = Objects.requireNonNull(properties);
        this.runner = runner;
        this.input = input instanceof BufferedReader buffered ? buffered : new BufferedReader(input);
        this.output = new PrintWriter(output, true);
        this.worker = Objects.requireNonNull(worker);
        selected = scenarios.entries().stream().filter(entry -> entry.spec().id().equals(initialScenario))
                .findFirst().orElse(null);
    }

    /** Blocks only on terminal input, except for orderly worker shutdown on EOF or /exit. */
    @Override
    public void run() {
        try {
            synchronized (control) {
                if (closed) {
                    return;
                }
                line("Agent CLI：仅本地、内存状态、重启不恢复；外发模型须用获准虚构资料。");
                showScenario();
                if (runner == null) {
                    line(MODEL_MISSING);
                }
                help();
            }
            while (true) {
                String text = input.readLine();
                synchronized (control) {
                    if (closed || text == null) {
                        break;
                    }
                    try {
                        if (!handle(text.strip())) {
                            break;
                        }
                    } catch (RuntimeException ex) {
                        // Provider, transport and storage exceptions may contain credentials or private URLs.
                        line("操作失败；未显示底层异常。请检查本地配置，并用 /status 查看真实状态。");
                    }
                }
            }
        } catch (IOException ex) {
            line("输入不可用，正在安全停止；未显示底层异常。");
        } finally {
            close();
        }
    }

    private boolean handle(String text) {
        if (text.isEmpty()) {
            return true;
        }
        if (!text.startsWith("/")) {
            refresh();
            deliverCancellation();
            start(text);
            return true;
        }
        String[] parts = text.split("\\s+", 2);
        String argument = parts.length == 2 ? parts[1].strip() : "";
        switch (parts[0]) {
            case "/scenario", "/status", "/answer", "/cancel" -> {
                refresh();
                deliverCancellation();
            }
            default -> { } // Discovery, help and exit remain usable even if Runner snapshot access fails.
        }
        switch (parts[0]) {
            case "/scenarios" -> {
                if (noArgument(argument)) {
                    scenarios.entries().forEach(entry -> line(entry.spec().id() + " — " + entry.description()));
                }
            }
            case "/scenario" -> select(argument);
            case "/status" -> {
                if (noArgument(argument)) {
                    showStatus();
                }
            }
            case "/answer" -> answer(argument);
            case "/cancel" -> {
                if (noArgument(argument)) {
                    cancel();
                }
            }
            case "/help" -> {
                if (noArgument(argument)) {
                    help();
                }
            }
            case "/exit" -> {
                if (noArgument(argument)) {
                    return false;
                }
            }
            default -> line("未知命令。使用 /help；不支持原始工具调用、目录变更或权限扩展。");
        }
        return true;
    }

    private boolean noArgument(String argument) {
        if (!argument.isEmpty()) {
            line("该命令不接受参数；使用 /help 查看格式。");
            return false;
        }
        return true;
    }

    private void select(String id) {
        if (busy()) {
            busyMessage();
            return;
        }
        ScenarioRegistry.Entry entry = scenarios.entries().stream()
                .filter(candidate -> candidate.spec().id().equals(id)).findFirst().orElse(null);
        if (entry == null) {
            line("场景不存在；用 /scenarios 查看后执行 /scenario <id>。");
            return;
        }
        selected = entry;
        showScenario();
    }

    private void start(String goal) {
        if (busy()) {
            busyMessage();
        } else if (runner == null) {
            line(MODEL_MISSING);
        } else if (selected == null) {
            line("未选择有效场景；请使用 /scenarios 和 /scenario <id>。");
        } else if (goal.length() > 16000) {
            line("目标必须为 1–16000 个字符。");
        } else if (properties.workspace() == null) {
            line("配置缺失：请配置 agent.workspace 的输入、输出及日志目录。");
        } else {
            previousRunId = runner.currentSnapshot().map(RunSnapshot::runId).orElse(null);
            runId = null;
            latest = null;
            stopReason = "";
            activeSpec = selected.spec();
            cancelRequested = false;
            cancelDelivered = false;
            RunRequest request = new RunRequest(goal, properties.workspace(), () -> cancelRequested || closed);
            AgentSpec spec = activeSpec;
            submit(() -> runner.run(spec, request));
            line("任务已提交；/status 查看状态，/cancel 取消。执行槽在 worker 真正结束前不会释放。");
        }
    }

    private void answer(String answer) {
        if (executing()) {
            line("忙碌：worker 尚未结束，请稍后 /answer；不会排队或重复提交回答。");
        } else if (latest == null || latest.status() != RunStatus.WAITING_INPUT || cancelRequested) {
            line("仅 WAITING_INPUT 且未取消时可以 /answer。");
        } else if (answer.isBlank() || answer.length() > 16000) {
            line("用法：/answer <1–16000 个字符的内容>。");
        } else {
            UUID id = runId;
            submit(() -> runner.resume(id, answer));
            line("回答已提交；继续同一 runId，预算与场景权限不变。");
        }
    }

    private void submit(Supplier<RunResult> action) {
        inFlight = worker.submit(() -> {
            synchronized (control) {
                if (cancelRequested || closed) {
                    return; // Cancelled before this worker entered Runner; do not create a new Run.
                }
            }
            try {
                RunResult result = action.get();
                synchronized (control) {
                    latest = result.snapshot();
                    runId = latest.runId();
                    stopReason = result.stopReason() != null && result.stopReason().matches("[A-Z][A-Z0-9_]{0,63}")
                            ? result.stopReason() : "UNKNOWN";
                    // A cancel may have published a newer snapshot after run/resume returned its result.
                    refresh();
                    deliverCancellation();
                    if (latest.status() == RunStatus.COMPLETED) {
                        line("模型回答（文本中的路径不是产物凭证，以真实产物列表为准）：");
                        line(result.message());
                    } else if (latest.status() == RunStatus.FAILED || latest.status() == RunStatus.STOPPED) {
                        line("任务未完成；请检查本地配置或运行限制。底层异常不在 CLI 展示。");
                    }
                    showSnapshot();
                }
            } catch (RuntimeException ex) {
                synchronized (control) {
                    line("任务执行失败；未显示底层异常，不代表任务完成。请用 /status 检查状态。");
                    try {
                        refresh();
                        deliverCancellation();
                        showSnapshot();
                    } catch (RuntimeException ignored) {
                        line("暂时无法读取运行状态；请检查本地配置。");
                    }
                }
            }
        });
    }

    private boolean executing() {
        // Future.cancel() is deliberately never used: isDone must mean the action has actually returned.
        return inFlight != null && !inFlight.isDone();
    }

    private boolean busy() {
        return executing() || latest != null
                && (latest.status() == RunStatus.RUNNING || latest.status() == RunStatus.WAITING_INPUT);
    }

    private void busyMessage() {
        line(latest != null && latest.status() == RunStatus.WAITING_INPUT
                ? "WAITING_INPUT：只能 /answer 或 /cancel，不能切换场景或提交新目标（/status、/help、/exit 可用）。"
                : "忙碌：任务或旧 worker 尚未结束；不能切换场景或提交新目标。");
    }

    private void refresh() {
        if (runner == null || inFlight == null) {
            return;
        }
        if (runId == null) {
            // On-demand only, including while synchronous run() is blocked in the model. No poller.
            runner.currentSnapshot().filter(snapshot -> !snapshot.runId().equals(previousRunId))
                    .ifPresent(snapshot -> {
                        latest = snapshot;
                        runId = snapshot.runId();
                    });
        } else {
            latest = runner.snapshot(runId);
        }
    }

    private void cancel() {
        if (!busy()) {
            line("当前没有可取消的任务。");
            return;
        }
        cancelRequested = true;
        deliverCancellation();
        line("已请求取消；旧 worker 结束前仍占用执行槽，已生成产物不会假称回滚。");
        showStatus();
    }

    private void deliverCancellation() {
        if (cancelRequested && !cancelDelivered && runId != null) {
            runner.cancel(runId);
            cancelDelivered = true;
            latest = runner.snapshot(runId);
        }
    }

    private void showStatus() {
        line("执行槽：" + (executing() ? "占用（worker 尚未结束）" : "空闲")
                + (cancelRequested ? "；已请求取消" : ""));
        if (latest == null) {
            line(executing() ? "runId 尚未发布；取消请求会保留。" : "尚无运行快照。");
        } else {
            showSnapshot();
        }
    }

    private void showSnapshot() {
        if (latest == null) {
            return;
        }
        RunLimits limits = activeSpec.limits();
        var metrics = latest.metrics();
        line("runId=" + latest.runId() + " 场景=" + latest.agentId() + " 状态=" + latest.status());
        if (latest.status() == RunStatus.CANCELLED) {
            line("停止原因：CANCELLED");
        } else if (latest.status() == RunStatus.FAILED || latest.status() == RunStatus.STOPPED) {
            line("停止原因：" + (stopReason.isEmpty() ? "UNKNOWN" : stopReason));
        }
        line("预算（真实快照）：模型轮数=" + metrics.modelRequests() + "/" + limits.maxModelRequests()
                + " 工具调用=" + metrics.toolCalls() + "/" + limits.maxToolCalls()
                + " 主动时间=" + metrics.activeTime() + "/" + limits.activeTimeout());
        line("usage=" + metrics.tokenUsage().map(usage -> "inputTokens=" + usage.inputTokens()
                + " outputTokens=" + usage.outputTokens()).orElse("unknown"));
        if (!latest.observations().isEmpty()) {
            line("最近工具=" + latest.observations().getLast().call().name());
        }
        latest.pendingQuestion().ifPresent(question -> {
            line("待答问题：" + question.question());
            for (int i = 0; i < question.options().size(); i++) {
                line("  " + (i + 1) + ". " + question.options().get(i));
            }
            line("请使用 /answer <内容> 或 /cancel。");
        });
        line("真实产物（仅 RunSnapshot.artifacts，不采信模型自述路径）：");
        if (latest.artifacts().isEmpty()) {
            line("  无");
        } else {
            latest.artifacts().forEach(artifact -> line("  " + artifact.id() + " -> " + absolute(artifact.path())));
        }
    }

    private void showScenario() {
        if (selected == null) {
            line("当前场景无效：请检查 agent.cli.scenario，或使用 /scenarios、/scenario <id>。");
        } else {
            AgentSpec spec = selected.spec();
            line("当前场景：" + spec.id() + " — " + selected.description());
            line("可用工具：" + String.join(", ", spec.toolNames().stream().sorted().toList()));
            line("场景约束：" + spec.instructions());
            line("运行限制：" + spec.limits());
        }
        var workspace = properties.workspace();
        if (workspace == null) {
            line("目录配置缺失：agent.workspace。");
        } else {
            line("输入目录：" + absolute(workspace.inputRoot()));
            line("输出目录：" + absolute(workspace.outputRoot()));
            line("日志目录：" + absolute(workspace.traceRoot()));
        }
        line("自然语言和回答不会扩大工具或目录权限；CLI 不接受原始工具调用。");
    }

    private static String absolute(Path path) {
        return path == null ? "未配置" : path.toAbsolutePath().normalize().toString();
    }

    private void help() {
        line("空闲时输入目标；命令：/scenarios | /scenario <id> | /status | /answer <内容> | /cancel | /exit | /help");
    }

    private void line(String text) {
        synchronized (output) {
            // Do not let model text or file names inject terminal control sequences.
            output.println(Objects.toString(text, "").replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", ""));
        }
    }

    /** EOF and /exit cancel, then join the real worker, rather than abandon writes or fake completion. */
    @Override
    public void close() {
        synchronized (control) {
            if (closed) {
                return;
            }
            closed = true;
            cancelRequested = true;
            try {
                refresh();
                deliverCancellation();
            } catch (RuntimeException ex) {
                line("停止请求未能确认；等待 worker 结束，不显示底层异常。");
            }
            worker.shutdown();
            line("正在退出；等待已提交的 worker 安全结束。");
        }
        try {
            worker.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
        } catch (InterruptedException ex) {
            // Keep shutdown orderly; never mark an interrupted Future as a free execution slot.
            Thread.currentThread().interrupt();
            line("退出等待被中断；worker 仍在停止中，不承诺重启恢复。");
        }
        // Reader/Writer are caller-owned, in particular System.in/System.out must remain open.
    }
}
