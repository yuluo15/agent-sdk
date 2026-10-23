package com.agent.agentcorelab.mcp.skill;

import com.agent.agentcorelab.tool.RiskLevel;
import com.agent.agentcorelab.tool.Tool;
import com.agent.agentcorelab.tool.ToolCatalog;
import com.agent.agentcorelab.tool.ToolContext;
import com.agent.agentcorelab.tool.ToolDefinition;
import com.agent.agentcorelab.tool.ToolOutcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Standard SKILL.md Parser, Loader and Registry Integration Tests")
class SkillMdParserAndLoaderTests {

    @Test
    @DisplayName("Parse standard complete SKILL.md with frontmatter, tools, metadata and SOP body")
    void testParseCompleteSkillMd() {
        String markdown = """
                ---
                name: crm-lead-qualification
                id: crm-lead-qualification
                description: Standard Operating Procedure for evaluating and qualifying enterprise B2B sales leads
                tags:
                  - crm
                  - sales
                  - qualification
                prerequisite_tools:
                  - crm_search_customer
                  - save_evaluation_note
                license: Apache-2.0
                version: 1.2.0
                metadata:
                  owner: sales-enablement@corp.internal
                  tier: high
                  slaMinutes: 30
                ---

                # Enterprise Lead Qualification SOP

                ## 1. Context & Objective
                Evaluate incoming inbound prospects before scheduling technical discovery.

                ## 2. Checklists & Steps
                1. Inspect account revenue and industry segment.
                2. Verify decision maker authority.
                3. Calculate initial scoring matrix.

                ---
                *Note: Follow corporate security guidelines for GDPR compliance.*
                """;

        SkillDefinition skill = SkillDefinition.fromMarkdown(markdown);

        assertEquals("crm-lead-qualification", skill.id());
        assertEquals("crm-lead-qualification", skill.name());
        assertEquals("Standard Operating Procedure for evaluating and qualifying enterprise B2B sales leads", skill.description());
        assertEquals(List.of("crm", "sales", "qualification"), skill.tags());
        assertEquals(List.of("crm_search_customer", "save_evaluation_note"), skill.prerequisiteTools());

        Map<String, Object> meta = skill.metadata();
        assertEquals("Apache-2.0", meta.get("license"));
        assertEquals("1.2.0", meta.get("version"));
        assertEquals("sales-enablement@corp.internal", meta.get("owner"));
        assertEquals("high", meta.get("tier"));
        assertEquals(30, ((Number) meta.get("slaMinutes")).intValue());

        // Verify SOP body preserved, including horizontal rules inside markdown
        assertTrue(skill.sop().contains("# Enterprise Lead Qualification SOP"));
        assertTrue(skill.sop().contains("1. Inspect account revenue and industry segment."));
        assertTrue(skill.sop().contains("*Note: Follow corporate security guidelines for GDPR compliance.*"));
        assertTrue(skill.sop().contains("---"));
    }

    @Test
    @DisplayName("Parse minimal valid SKILL.md with human readable name and slug id conversion")
    void testParseMinimalSkillMdWithSlugName() {
        String markdown = """
                ---
                name: Customer Onboarding Flow
                description: Guides onboarding for newly signed accounts
                ---

                ### Onboarding SOP
                Step 1: Send welcome packet.
                Step 2: Provision cloud sandbox.
                """;

        SkillDefinition skill = SkillParser.parse(markdown);

        assertEquals("customer-onboarding-flow", skill.id());
        assertEquals("Customer Onboarding Flow", skill.name());
        assertEquals("Guides onboarding for newly signed accounts", skill.description());
        assertTrue(skill.tags().isEmpty());
        assertTrue(skill.prerequisiteTools().isEmpty());
        assertTrue(skill.sop().contains("Step 1: Send welcome packet."));
    }

    @Test
    @DisplayName("Parse SKILL.md with alternative frontmatter keys (category, comma-separated tools)")
    void testParseAlternativeKeys() {
        String markdown = """
                ---
                name: financial_reconciliation
                description: Monthly invoice and ledger balancing procedure
                category: finance, accounting
                tools: ledger_query, bank_statement_fetcher
                author: Finance Ops Team
                ---

                ## Reconciliation Steps
                Match ledger lines against bank statement.
                """;

        SkillDefinition skill = SkillDefinition.fromMarkdown(markdown);

        assertEquals("financial_reconciliation", skill.id());
        assertEquals(List.of("finance", "accounting"), skill.tags());
        assertEquals(List.of("ledger_query", "bank_statement_fetcher"), skill.prerequisiteTools());
        assertEquals("Finance Ops Team", skill.metadata().get("author"));
    }

    @Test
    @DisplayName("Round-trip serialization to SKILL.md markdown and re-parse")
    void testRoundTripSerialization() {
        SkillDefinition original = SkillDefinition.builder("incident-postmortem")
                .name("Incident Postmortem Standard")
                .description("Guidelines for documenting P1/P2 production incidents")
                .tags("ops", "sre", "incident")
                .prerequisiteTools("pagerduty_query", "slack_notify")
                .sop("### Postmortem Procedure\n1. Timeline reconstruction\n2. Root cause analysis (5 Whys)")
                .metadata("templateVersion", "2.0")
                .build();

        String serializedMd = original.toMarkdown();
        assertNotNull(serializedMd);
        assertTrue(serializedMd.startsWith("---\n"));
        assertTrue(serializedMd.contains("name: Incident Postmortem Standard"));
        assertTrue(serializedMd.contains("id: incident-postmortem"));

        SkillDefinition roundTripped = SkillDefinition.fromMarkdown(serializedMd);
        assertEquals(original.id(), roundTripped.id());
        assertEquals(original.name(), roundTripped.name());
        assertEquals(original.description(), roundTripped.description());
        assertEquals(original.tags(), roundTripped.tags());
        assertEquals(original.prerequisiteTools(), roundTripped.prerequisiteTools());
        assertEquals(original.sop().trim(), roundTripped.sop().trim());
        assertEquals("2.0", roundTripped.metadata().get("templateVersion"));
    }

    @Test
    @DisplayName("Validation constraints reject malformed SKILL.md formats")
    void testValidationConstraints() {
        // 1. Null or blank content
        assertThrows(IllegalArgumentException.class, () -> SkillParser.parse(null));
        assertThrows(IllegalArgumentException.class, () -> SkillParser.parse("   \n\t  "));

        // 2. Missing opening frontmatter delimiter
        assertThrows(IllegalArgumentException.class, () -> SkillParser.parse("# Just a markdown title without frontmatter"));

        // 3. Missing closing frontmatter delimiter
        assertThrows(IllegalArgumentException.class, () -> SkillParser.parse("---\nname: test\ndescription: missing closing delimiter"));

        // 4. Empty frontmatter
        assertThrows(IllegalArgumentException.class, () -> SkillParser.parse("---\n\n---\n# Body"));

        // 5. Malformed YAML
        assertThrows(IllegalArgumentException.class, () -> SkillParser.parse("---\nname: [unclosed array\ndescription: bad\n---\nBody"));

        // 6. Missing name and id
        assertThrows(IllegalArgumentException.class, () -> SkillParser.parse("---\ndescription: Has description but no name or id\n---\nBody"));

        // 7. Missing description
        assertThrows(IllegalArgumentException.class, () -> SkillParser.parse("---\nname: valid-name\n---\nBody"));

        // 8. Invalid identifier characters
        assertThrows(IllegalArgumentException.class, () -> SkillParser.parse("---\nid: invalid id with spaces\ndescription: test\n---\nBody"));
    }

    @Test
    @DisplayName("SkillLoader and DefaultSkillRegistry load directory with multiple SKILL.md files")
    void testDirectoryLoadingAndRegistry(@TempDir Path tempDir) throws IOException {
        // Create directory structure:
        // tempDir/
        //   lead-qualification/SKILL.md
        //   sre/incident-response/SKILL.md
        //   quick-helper.skill.md
        //   README.md (should be ignored)

        Path skill1Dir = tempDir.resolve("lead-qualification");
        Files.createDirectories(skill1Dir);
        Files.writeString(skill1Dir.resolve("SKILL.md"), """
                ---
                name: lead-qualification
                description: Qualify incoming leads
                tags: [sales]
                prerequisite_tools: [crm_tool]
                ---
                # Lead Qualification SOP
                Inspect lead profile and budget.
                """);

        Path skill2Dir = tempDir.resolve("sre").resolve("incident-response");
        Files.createDirectories(skill2Dir);
        Files.writeString(skill2Dir.resolve("SKILL.md"), """
                ---
                name: incident-response
                description: Mitigate active production outages
                tags: [ops, sre]
                ---
                # Incident SOP
                Assess blast radius and escalate.
                """);

        Files.writeString(tempDir.resolve("quick-helper.skill.md"), """
                ---
                name: quick-helper
                description: Quick triage assistant
                tags: [support]
                ---
                Triage customer ticket.
                """);

        Files.writeString(tempDir.resolve("README.md"), "# Project Readme\nNot a skill.");

        DefaultSkillRegistry registry = new DefaultSkillRegistry();
        List<SkillDefinition> loaded = registry.loadFromDirectory(tempDir);

        assertEquals(3, loaded.size());
        assertEquals(3, registry.listAll().size());
        assertTrue(registry.hasSkill("lead-qualification"));
        assertTrue(registry.hasSkill("incident-response"));
        assertTrue(registry.hasSkill("quick-helper"));
        assertFalse(registry.hasSkill("README"));

        // Verify tags filter
        assertEquals(1, registry.findByTag("sales").size());
        assertEquals(1, registry.findByTag("sre").size());

        // Verify single file loader
        SkillDefinition singleLoaded = registry.loadFromFile(skill1Dir.resolve("SKILL.md"));
        assertEquals("lead-qualification", singleLoaded.id());
    }

    @Test
    @DisplayName("End-to-End SkillConsultantTool consults skill loaded from SKILL.md")
    void testConsultantToolWithLoadedSkill() {
        String skillMd = """
                ---
                name: cloud-resource-provisioning
                id: cloud-resource-provisioning
                description: Provisions virtual machines and VPC networks in enterprise cloud
                tags:
                  - cloud
                  - infra
                prerequisite_tools:
                  - cloud_deploy_vm
                  - audit_log_writer
                metadata:
                  cloudProvider: AWS
                  complianceLevel: PCI-DSS
                ---

                ### SOP: Cloud Resource Provisioning
                1. Verify IAM permissions of requesting user.
                2. Allocate subnet CIDR blocks.
                3. Spin up compute instances with mandatory tags.
                4. Write audit log entry.
                """;

        DefaultSkillRegistry registry = new DefaultSkillRegistry();
        SkillDefinition registered = registry.registerSkillMd(skillMd);
        assertNotNull(registered);
        assertEquals("cloud-resource-provisioning", registered.id());

        // Tool fulfilling one prerequisite but missing audit_log_writer
        Tool vmTool = new Tool() {
            @Override
            public ToolDefinition definition() {
                return new ToolDefinition("cloud_deploy_vm", "Deploys VM", "{\"type\":\"object\"}", RiskLevel.READ_ONLY);
            }
            @Override
            public ToolOutcome execute(String argumentsJson, ToolContext context) {
                return new ToolOutcome.Success("{\"status\":\"vm-provisioned\"}");
            }
        };

        ToolCatalog catalog = new ToolCatalog(List.of(vmTool));
        SkillConsultantTool consultantTool = new SkillConsultantTool(registry, () -> catalog);

        ToolOutcome outcome = consultantTool.execute("{\"skillId\":\"cloud-resource-provisioning\"}", null);
        assertInstanceOf(ToolOutcome.Success.class, outcome);

        String json = ((ToolOutcome.Success) outcome).outputJson();
        assertTrue(json.contains("\"ok\":true"));
        assertTrue(json.contains("\"skillId\":\"cloud-resource-provisioning\""));
        assertTrue(json.contains("\"prerequisitesSatisfied\":false"));
        assertTrue(json.contains("audit_log_writer"));
        assertTrue(json.contains("cloud_deploy_vm"));
        assertTrue(json.contains("1. Verify IAM permissions of requesting user."));
        assertTrue(json.contains("complianceLevel"));
        assertTrue(json.contains("PCI-DSS"));
    }
}
