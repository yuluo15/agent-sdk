package com.agent.agentcorelab.bootstrap;

import com.agent.agentcorelab.agent.run.RunLimits;
import com.agent.agentcorelab.workspace.Workspace;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

@ConfigurationProperties(prefix = "agent")
public final class AgentProperties {
    private final RunLimits limits;
    private final Workspace workspace;

    @ConstructorBinding
    public AgentProperties(@DefaultValue LimitsSettings limits, @DefaultValue WorkspaceSettings workspace) {
        this(new RunLimits(limits.maxModelRequests(), limits.maxToolCalls(), limits.modelTimeout(),
                        limits.activeTimeout(), limits.maxContextTokens(), limits.outputTokenReserve(),
                        limits.maxRepeatedToolCalls(), limits.maxConsecutiveToolErrors(), limits.maxToolResultBytes(),
                        limits.waitingTimeout(), limits.maxModelRetries()),
                new Workspace(workspace.inputRoot(), workspace.outputRoot(), workspace.traceRoot(),
                        workspace.createInputIfMissing()));
    }

    public AgentProperties(RunLimits limits, Workspace workspace) {
        this.limits = Objects.requireNonNull(limits, "limits");
        this.workspace = Objects.requireNonNull(workspace, "workspace");
    }

    public RunLimits limits() {
        return limits;
    }

    public Workspace workspace() {
        return workspace;
    }

    public record LimitsSettings(
            @DefaultValue("16") int maxModelRequests,
            @DefaultValue("30") int maxToolCalls,
            @DefaultValue("60s") Duration modelTimeout,
            @DefaultValue("300s") Duration activeTimeout,
            @DefaultValue("32768") int maxContextTokens,
            @DefaultValue("4096") int outputTokenReserve,
            @DefaultValue("3") int maxRepeatedToolCalls,
            @DefaultValue("3") int maxConsecutiveToolErrors,
            @DefaultValue("16384") int maxToolResultBytes,
            @DefaultValue("15m") Duration waitingTimeout,
            @DefaultValue("1") int maxModelRetries
    ) {
    }

    public record WorkspaceSettings(
            @DefaultValue("./workspace/input") Path inputRoot,
            @DefaultValue("./workspace/output") Path outputRoot,
            @DefaultValue("./workspace/traces") Path traceRoot,
            @DefaultValue("false") boolean createInputIfMissing
    ) {
    }
}
