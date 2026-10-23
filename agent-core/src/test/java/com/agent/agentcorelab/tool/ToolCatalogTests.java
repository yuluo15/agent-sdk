package com.agent.agentcorelab.tool;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolCatalogTests {

    @Test
    void startsWithNoTools() {
        assertTrue(new ToolCatalog(List.of()).definitions().isEmpty());
    }

    @Test
    void rejectsDuplicateNames() {
        assertThrows(IllegalArgumentException.class,
                () -> new ToolCatalog(List.of(tool("read"), tool("read"))));
    }

    @Test
    void selectedCatalogOnlyExposesAllowedTools() {
        ToolCatalog catalog = new ToolCatalog(List.of(tool("read"), tool("write")));
        ToolCatalog selected = catalog.select(Set.of("read"));

        assertEquals(List.of("read"), selected.definitions().stream().map(ToolDefinition::name).toList());
        assertEquals("read", selected.require("read").definition().name());
        assertThrows(IllegalArgumentException.class, () -> selected.require("write"));
        assertEquals(2, catalog.definitions().size());
    }

    @Test
    void rejectsUnknownToolInSelection() {
        ToolCatalog catalog = new ToolCatalog(List.of(tool("read")));
        assertThrows(IllegalArgumentException.class, () -> catalog.select(Set.of("missing")));
    }

    @Test
    void ordersSelectedDefinitionsByName() {
        ToolCatalog catalog = new ToolCatalog(List.of(tool("write"), tool("read")));
        assertEquals(List.of("read", "write"),
                catalog.select(Set.of("write", "read")).definitions().stream()
                        .map(ToolDefinition::name).toList());
    }

    private Tool tool(String name) {
        return new TestTool(new ToolDefinition(name, "Test fixture", "{\"type\":\"object\"}"));
    }

    private record TestTool(ToolDefinition definition) implements Tool {
        @Override
        public ToolOutcome execute(String argumentsJson, ToolContext context) {
            return new ToolOutcome.Success("{}");
        }
    }
}
