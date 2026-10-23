package com.agent.agentcorelab.sample.analysis;

import com.agent.agentcorelab.sample.files.SampleTool;
import com.agent.agentcorelab.tool.ToolContext;
import com.agent.agentcorelab.workspace.WorkspaceException;
import com.agent.agentcorelab.workspace.WorkspaceJson;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.commons.csv.CSVRecord;

import java.io.IOException;
import java.util.List;

public final class InspectDatasetTool extends SampleTool {
    public InspectDatasetTool() {
        super("inspectDataset", "Inspect CSV headers, rough type hints and up to three sample rows. Hints are not numeric validation.", fileIdSchema());
    }

    @Override protected String executeValidated(JsonNode arguments, ToolContext context) throws IOException {
        String fileId = arguments.get("fileId").asText();
        CsvDataset dataset = CsvDataset.load(context.resources(), fileId, context::checkActive);
        ObjectNode data = WorkspaceJson.object().put("rowCount", dataset.rows().size()).put("sha256", dataset.file().sha256());
        ArrayNode columns = data.putArray("columns");
        ObjectNode types = data.putObject("typeHints");
        for (String name : dataset.columns()) {
            context.checkActive();
            columns.add(name);
            boolean numbers = true;
            boolean dates = true;
            for (CSVRecord row : dataset.rows()) {
                context.checkActive();
                if (numbers) {
                    try { CsvDataset.number(row.get(name)); } catch (WorkspaceException e) { numbers = false; }
                }
                if (dates) {
                    try { CsvDataset.date(row.get(name)); } catch (WorkspaceException e) { dates = false; }
                }
                if (!numbers && !dates) break;
            }
            types.put(name, numbers ? "decimal" : dates ? "date" : "text");
        }
        ArrayNode samples = data.putArray("samples");
        for (CSVRecord row : dataset.rows().subList(0, Math.min(3, dataset.rows().size()))) {
            ObjectNode sample = samples.addObject();
            for (String name : dataset.columns()) {
                String value = row.get(name);
                int count = value.codePointCount(0, value.length());
                sample.putObject(name).put("value", count > 120 ? value.substring(0, value.offsetByCodePoints(0, 120)) : value)
                        .put("truncated", count > 120);
            }
        }
        context.checkActive();
        return context.resources().observe(definition().name(), arguments, data, List.of(fileId));
    }
}
