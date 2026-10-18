package com.agent.agentcorelab.tool.builtin;

import com.agent.agentcorelab.tool.Tool;
import com.agent.agentcorelab.tool.ToolContext;
import com.agent.agentcorelab.tool.ToolDefinition;
import com.agent.agentcorelab.tool.ToolOutcome;

public final class UpdatePlanTool implements Tool {
    public static final String NAME = "updatePlan";
    private static final ToolDefinition DEFINITION = new ToolDefinition(NAME,
            "Update or initialize the explicit multi-step task plan. Must be the only tool call in this response. "
                    + "State changes must be accompanied by valid reasons and evidence references.", """
            {"type":"object","properties":{
              "expectedRevision":{"type":"integer","minimum":0},
              "goalVersion":{"type":"integer","minimum":1},
              "reason":{"type":"string","minLength":1,"maxLength":500},
              "steps":{
                "type":"array",
                "maxItems":10,
                "items":{
                  "type":"object",
                  "properties":{
                    "stepId":{"type":"string","minLength":1,"maxLength":50},
                    "title":{"type":"string","minLength":1,"maxLength":200},
                    "description":{"type":"string","maxLength":1000},
                    "status":{"type":"string","enum":["PENDING","ACTIVE","DONE","BLOCKED","DROPPED"]},
                    "evidenceRefs":{"type":"array","items":{"type":"string"}},
                    "reason":{"type":"string","maxLength":500}
                  },
                  "required":["stepId","title","status"],
                  "additionalProperties":false
                }
              }
            },"required":["expectedRevision","goalVersion","steps"],"additionalProperties":false}
            """);

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public ToolOutcome execute(String argumentsJson, ToolContext context) {
        throw new IllegalStateException("updatePlan is controlled by the Runner");
    }
}
