package com.agent.agentcorelab.mcp.skill;

import com.agent.agentcorelab.mcp.protocol.McpJsonRpcCodec;
import com.agent.agentcorelab.tool.RiskLevel;
import com.agent.agentcorelab.tool.Tool;
import com.agent.agentcorelab.tool.ToolCatalog;
import com.agent.agentcorelab.tool.ToolContext;
import com.agent.agentcorelab.tool.ToolDefinition;
import com.agent.agentcorelab.tool.ToolOutcome;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * System tool enabling the agent to dynamically consult the {@link SkillRegistry},
 * inspect prerequisite tools, and retrieve full Standard Operating Procedures (SOPs).
 */
public final class SkillConsultantTool implements Tool {

    public static final String TOOL_NAME = "consult_skill";

    private final SkillRegistry registry;
    private final Supplier<ToolCatalog> catalogSupplier;
    private final ObjectMapper mapper;
    private final ToolDefinition definition;

    public SkillConsultantTool(SkillRegistry registry) {
        this(registry, () -> null, McpJsonRpcCodec.mapper());
    }

    public SkillConsultantTool(SkillRegistry registry, Supplier<ToolCatalog> catalogSupplier) {
        this(registry, catalogSupplier, McpJsonRpcCodec.mapper());
    }

    public SkillConsultantTool(SkillRegistry registry, Supplier<ToolCatalog> catalogSupplier, ObjectMapper mapper) {
        this.registry = Objects.requireNonNull(registry, "registry cannot be null");
        this.catalogSupplier = catalogSupplier != null ? catalogSupplier : () -> null;
        this.mapper = mapper != null ? mapper : McpJsonRpcCodec.mapper();

        String schema = """
                {
                  "type": "object",
                  "properties": {
                    "skillId": {
                      "type": "string",
                      "description": "Unique identifier of the enterprise skill to consult"
                    }
                  },
                  "required": ["skillId"]
                }
                """;

        this.definition = new ToolDefinition(
                TOOL_NAME,
                "Consults the enterprise skill registry to retrieve the Standard Operating Procedure (SOP), checklist, and tool requirements for a given skill ID.",
                schema,
                RiskLevel.READ_ONLY
        );
    }

    @Override
    public ToolDefinition definition() {
        return definition;
    }

    @Override
    public ToolOutcome execute(String argumentsJson, ToolContext context) {
        if (context != null) {
            context.checkActive();
        }

        String skillId;
        try {
            JsonNode args = (argumentsJson == null || argumentsJson.isBlank())
                    ? mapper.createObjectNode()
                    : mapper.readTree(argumentsJson);
            skillId = args.path("skillId").asText(null);
            if (skillId == null || skillId.isBlank()) {
                return new ToolOutcome.Failure("INVALID_ARGUMENT", "Argument 'skillId' is required", false);
            }
        } catch (JsonProcessingException e) {
            return new ToolOutcome.Failure("INVALID_ARGUMENT", "Malformed arguments JSON: " + e.getMessage(), false);
        }

        Optional<SkillDefinition> skillOpt = registry.get(skillId.trim());
        if (skillOpt.isEmpty()) {
            List<String> available = registry.listAll().stream().map(SkillDefinition::id).toList();
            ObjectNode notFound = mapper.createObjectNode();
            notFound.put("ok", false);
            notFound.put("error", "Skill not found: '" + skillId + "'");
            ArrayNode availArray = notFound.putArray("availableSkills");
            available.forEach(availArray::add);
            try {
                return new ToolOutcome.Success(mapper.writeValueAsString(notFound));
            } catch (JsonProcessingException e) {
                return new ToolOutcome.Failure("NOT_FOUND", "Skill not found: " + skillId, false);
            }
        }

        SkillDefinition skill = skillOpt.get();
        ToolCatalog catalog = catalogSupplier.get();
        List<String> missingTools = new ArrayList<>();
        if (catalog != null && !skill.prerequisiteTools().isEmpty()) {
            for (String requiredTool : skill.prerequisiteTools()) {
                if (catalog.find(requiredTool).isEmpty()) {
                    missingTools.add(requiredTool);
                }
            }
        }

        ObjectNode response = mapper.createObjectNode();
        response.put("ok", true);
        response.put("skillId", skill.id());
        response.put("name", skill.name());
        response.put("description", skill.description());
        response.put("prerequisitesSatisfied", missingTools.isEmpty());

        ArrayNode missingNode = response.putArray("missingPrerequisiteTools");
        missingTools.forEach(missingNode::add);

        ArrayNode reqNode = response.putArray("prerequisiteTools");
        skill.prerequisiteTools().forEach(reqNode::add);

        response.put("sop", skill.sop());
        response.set("metadata", mapper.valueToTree(skill.metadata()));

        try {
            return new ToolOutcome.Success(mapper.writeValueAsString(response));
        } catch (JsonProcessingException e) {
            return new ToolOutcome.Failure("SERIALIZATION_FAILED", "Failed serializing skill details: " + e.getMessage(), true);
        }
    }
}
