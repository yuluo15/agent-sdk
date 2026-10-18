package com.agent.agentcorelab.mcp.skill;

import com.agent.agentcorelab.tool.RiskLevel;
import com.agent.agentcorelab.tool.Tool;
import com.agent.agentcorelab.tool.ToolCatalog;
import com.agent.agentcorelab.tool.ToolContext;
import com.agent.agentcorelab.tool.ToolDefinition;
import com.agent.agentcorelab.tool.ToolOutcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Enterprise SkillRegistry and SkillConsultantTool Tests")
class SkillRegistryAndToolTests {

    @Test
    @DisplayName("SkillDefinition builder and validation constraints")
    void testSkillDefinition() {
        SkillDefinition skill = SkillDefinition.builder("crm-lead-qualification")
                .name("CRM Lead Qualification SOP")
                .description("Standard procedure for evaluating CRM prospect fit and opportunity stage")
                .tags("crm", "sales", "enterprise")
                .prerequisiteTools("read_file", "save_work_note")
                .sop("1. Read customer profile\n2. Score budget and authority\n3. Save assessment note")
                .metadata("owner", "sales-ops")
                .build();

        assertEquals("crm-lead-qualification", skill.id());
        assertEquals("CRM Lead Qualification SOP", skill.name());
        assertEquals(3, skill.tags().size());
        assertEquals(2, skill.prerequisiteTools().size());
        assertEquals("sales-ops", skill.metadata().get("owner"));

        assertThrows(IllegalArgumentException.class, () ->
                SkillDefinition.builder("invalid name with spaces").description("test").build()
        );
    }

    @Test
    @DisplayName("DefaultSkillRegistry queries, tag filters, and search")
    void testSkillRegistryOperations() {
        SkillRegistry registry = new DefaultSkillRegistry();

        SkillDefinition s1 = SkillDefinition.builder("crm-qualification")
                .description("Qualifies CRM inbound leads")
                .tags("crm", "sales")
                .build();

        SkillDefinition s2 = SkillDefinition.builder("financial-audit")
                .description("Audits financial balance sheets")
                .tags("finance", "compliance")
                .build();

        registry.register(s1);
        registry.register(s2);

        assertTrue(registry.hasSkill("crm-qualification"));
        assertFalse(registry.hasSkill("unknown-skill"));

        Optional<SkillDefinition> retrieved = registry.get("crm-qualification");
        assertTrue(retrieved.isPresent());
        assertEquals("crm-qualification", retrieved.get().id());

        List<SkillDefinition> crmSkills = registry.findByTag("crm");
        assertEquals(1, crmSkills.size());
        assertEquals("crm-qualification", crmSkills.get(0).id());

        List<SkillDefinition> searchResult = registry.search("balance");
        assertEquals(1, searchResult.size());
        assertEquals("financial-audit", searchResult.get(0).id());
    }

    @Test
    @DisplayName("SkillPromptContributor renders concise markdown index")
    void testSkillPromptContributor() {
        SkillRegistry registry = new DefaultSkillRegistry();
        registry.register(SkillDefinition.builder("git-release")
                .name("Git Release Workflow")
                .description("Handles automated branch cuts and changelog generation")
                .prerequisiteTools("git_tag", "write_changelog")
                .build());

        String index = SkillPromptContributor.renderSkillIndex(registry);
        assertTrue(index.contains("### Enterprise Skills & SOPs Available"));
        assertTrue(index.contains("`git-release`"));
        assertTrue(index.contains("Requires tools: git_tag, write_changelog"));
    }

    @Test
    @DisplayName("SkillConsultantTool validates prerequisites and returns detailed SOP")
    void testSkillConsultantTool() {
        SkillRegistry registry = new DefaultSkillRegistry();
        registry.register(SkillDefinition.builder("lead-scoring")
                .name("Lead Scoring")
                .description("Scores lead potential")
                .prerequisiteTools("db_query", "email_sender")
                .sop("### Lead Scoring Steps:\nStep 1: Check ARR\nStep 2: Score 1-100")
                .build());

        // Dummy tool fulfilling one prerequisite
        Tool dbTool = new Tool() {
            @Override
            public ToolDefinition definition() {
                return new ToolDefinition("db_query", "Queries DB", "{\"type\":\"object\"}", RiskLevel.READ_ONLY);
            }
            @Override
            public ToolOutcome execute(String argumentsJson, ToolContext context) {
                return new ToolOutcome.Success("{\"ok\":true}");
            }
        };

        ToolCatalog catalog = new ToolCatalog(List.of(dbTool));
        SkillConsultantTool consultantTool = new SkillConsultantTool(registry, () -> catalog);

        assertEquals("consult_skill", consultantTool.definition().name());

        // 1. Consult existing skill with 1 missing prerequisite (email_sender)
        ToolOutcome outcome = consultantTool.execute("{\"skillId\":\"lead-scoring\"}", null);
        assertInstanceOf(ToolOutcome.Success.class, outcome);
        String json = ((ToolOutcome.Success) outcome).outputJson();
        assertTrue(json.contains("\"prerequisitesSatisfied\":false"));
        assertTrue(json.contains("email_sender"));
        assertTrue(json.contains("Step 1: Check ARR"));

        // 2. Consult non-existent skill
        ToolOutcome notFoundOutcome = consultantTool.execute("{\"skillId\":\"ghost-skill\"}", null);
        assertInstanceOf(ToolOutcome.Success.class, notFoundOutcome);
        String notFoundJson = ((ToolOutcome.Success) notFoundOutcome).outputJson();
        assertTrue(notFoundJson.contains("\"ok\":false"));
        assertTrue(notFoundJson.contains("Skill not found"));
        assertTrue(notFoundJson.contains("lead-scoring"));
    }
}
