package com.agent.agentcorelab.sample.files;

import com.agent.agentcorelab.agent.CompletionCheck;
import com.agent.agentcorelab.scenario.ScenarioPlugin;
import com.agent.agentcorelab.tool.Tool;

import java.util.List;
import java.util.Objects;

public final class FileInspectionScenario implements ScenarioPlugin {
    private final List<Tool> tools;
    private final CompletionCheck check = new FileInspectionCompletionCheck();

    public FileInspectionScenario() { this(ListWorkspaceFilesTool.INSTANCE); }

    /** Pass the same listing instance to both sample plugins when assembling the application. */
    public FileInspectionScenario(ListWorkspaceFilesTool listing) { tools = List.of(Objects.requireNonNull(listing)); }

    @Override public String id() { return "file-inspection"; }
    @Override public String description() { return "列举受控输入目录的实际文件；不读取正文、不要求报告。"; }
    @Override public String instructions() {
        return "先实际调用 listWorkspaceFiles，再依据本次观察说明可用文件；空目录也如实说明。"
                + "不要猜测文件存在性，不读取其他目录，不编造报告或产物。此场景不要求生成报告。";
    }
    @Override public List<Tool> tools() { return tools; }
    @Override public CompletionCheck completionCheck() { return check; }
}
