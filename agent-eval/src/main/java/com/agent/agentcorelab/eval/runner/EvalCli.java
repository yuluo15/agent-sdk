package com.agent.agentcorelab.eval.runner;

import com.agent.agentcorelab.eval.adapter.FakeReadonlySupportTools;
import com.agent.agentcorelab.eval.dataset.DatasetLoader;
import com.agent.agentcorelab.eval.dataset.EvalCase;
import com.agent.agentcorelab.eval.report.EvalReport;
import com.agent.agentcorelab.eval.report.EvalReportGenerator;
import com.agent.agentcorelab.eval.replay.ReplayModelGateway;
import com.agent.agentcorelab.eval.replay.ReplayRecorder;
import com.agent.agentcorelab.model.ModelGateway;
import com.agent.agentcorelab.tool.ToolCatalog;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;

/**
 * Command-line entry point for running Phase 0 evaluation suites and emitting reports.
 */
public class EvalCli {

    public static void main(String[] args) throws Exception {
        String datasetPath = "eval/datasets/phase0-v1.json";
        String replayPath = null;
        String outputDir = "target/eval-reports";
        String reportId = "EVAL-" + UUID.randomUUID().toString().substring(0, 8);

        for (int i = 0; i < args.length; i++) {
            if ("--dataset".equals(args[i]) && i + 1 < args.length) {
                datasetPath = args[++i];
            } else if ("--replay".equals(args[i]) && i + 1 < args.length) {
                replayPath = args[++i];
            } else if ("--output-dir".equals(args[i]) && i + 1 < args.length) {
                outputDir = args[++i];
            } else if ("--report-id".equals(args[i]) && i + 1 < args.length) {
                reportId = args[++i];
            }
        }

        System.out.println("=================================================");
        System.out.println("Starting Agent SDK Phase 0 Evaluation Harness");
        System.out.println("Report ID   : " + reportId);
        System.out.println("Dataset     : " + datasetPath);
        System.out.println("Replay Mode : " + (replayPath != null ? replayPath : "Synthetic / Gateway"));
        System.out.println("Output Dir  : " + outputDir);
        System.out.println("=================================================");

        List<EvalCase> cases;
        if (datasetPath.startsWith("/") || datasetPath.contains(":") || Paths.get(datasetPath).isAbsolute() || Files.exists(Paths.get(datasetPath))) {
            cases = DatasetLoader.loadFromFile(Paths.get(datasetPath));
        } else {
            cases = DatasetLoader.loadFromClasspath(datasetPath);
        }
        System.out.println("Loaded " + cases.size() + " evaluation case(s).");

        ToolCatalog catalog = FakeReadonlySupportTools.createCatalog();

        ModelGateway gateway;
        if (replayPath != null && Files.exists(Paths.get(replayPath))) {
            System.out.println("Initializing deterministic replay from " + replayPath);
            ReplayRecorder recorder = ReplayRecorder.loadFromFile(Paths.get(replayPath));
            gateway = new ReplayModelGateway(recorder);
        } else {
            System.out.println("No replay path specified; using default mock evaluation gateway.");
            gateway = (instructions, goal, tools) -> null;
        }

        EvalRunner runner = new EvalRunner(gateway, catalog);
        EvalReport report = runner.executeSuite(cases, reportId);

        String markdown = EvalReportGenerator.generateMarkdown(report);
        String json = EvalReportGenerator.generateJson(report);

        try {
            Path out = Paths.get(outputDir);
            Files.createDirectories(out);
            Path mdFile = out.resolve("eval-report-" + reportId + ".md");
            Path jsonFile = out.resolve("eval-report-" + reportId + ".json");
            Files.writeString(mdFile, markdown);
            Files.writeString(jsonFile, json);

            System.out.println("\nEvaluation completed successfully!");
            System.out.println("Markdown Report written to: " + mdFile.toAbsolutePath());
            System.out.println("JSON Report written to    : " + jsonFile.toAbsolutePath());
            System.out.println("\n--- Summary ---");
            System.out.println("Total: " + report.metrics().totalCases()
                    + " | Passed: " + report.metrics().passedCases()
                    + " | Failed: " + report.metrics().failedCases()
                    + " | Safety Blockers: " + report.metrics().safetyFailures());
            System.out.println("Task Success Rate: " + String.format("%.1f%%", report.metrics().taskSuccessRate()));
        } catch (IOException e) {
            System.err.println("Failed to write report files: " + e.getMessage());
        }

        if (report.metrics().safetyFailures() > 0) {
            System.exit(2);
        } else if (report.metrics().failedCases() > 0) {
            System.exit(1);
        }
    }
}
