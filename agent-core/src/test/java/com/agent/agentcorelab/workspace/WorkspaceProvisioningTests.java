package com.agent.agentcorelab.workspace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class WorkspaceProvisioningTests {
    @TempDir Path root;

    @Test
    void existingConstructorDoesNotCreateMissingInput() {
        Workspace workspace = new Workspace(root.resolve("input"), root.resolve("output"), root.resolve("traces"));
        assertFalse(workspace.createInputIfMissing());
        WorkspaceException failure = assertThrows(WorkspaceException.class,
                () -> new WorkspaceSession(workspace, UUID.randomUUID()));
        assertEquals("WORKSPACE_INVALID", failure.code());
        assertTrue(failure.getMessage().contains("create-input-if-missing"));
        assertFalse(Files.exists(workspace.inputRoot()));
        assertFalse(Files.exists(workspace.outputRoot()));
        assertFalse(Files.exists(workspace.traceRoot()));
    }

    @Test
    void optedInWorkspaceCreatesEmptyInputAndPreservesExistingContents() throws Exception {
        Workspace workspace = new Workspace(root.resolve("nested/input"), root.resolve("output"), root.resolve("traces"), true);
        try (WorkspaceSession session = new WorkspaceSession(workspace, UUID.randomUUID())) {
            assertTrue(Files.isDirectory(workspace.inputRoot()));
            assertTrue(session.listFiles(() -> {}).isEmpty());
            Files.writeString(workspace.inputRoot().resolve("note.txt"), "retained");
        }
        try (WorkspaceSession session = new WorkspaceSession(workspace, UUID.randomUUID())) {
            var file = session.listFiles(() -> {}).getFirst();
            assertEquals("retained", session.read(file.fileId()).utf8());
        }
    }

    @Test
    void optInDoesNotReplaceAnExistingFile() throws Exception {
        Path input = Files.writeString(root.resolve("input"), "must not overwrite");
        Workspace workspace = new Workspace(input, root.resolve("output"), root.resolve("traces"), true);
        assertEquals("WORKSPACE_INVALID", assertThrows(WorkspaceException.class,
                () -> new WorkspaceSession(workspace, UUID.randomUUID())).code());
        assertEquals("must not overwrite", Files.readString(input));
        assertFalse(Files.exists(workspace.outputRoot()));
    }

    @Test
    void invalidRootRelationshipsAreRejectedBeforeAnyDirectoryCreation() {
        Path input = root.resolve("input");
        Path output = root.resolve("output");
        Path traces = root.resolve("traces");
        for (Workspace workspace : List.of(new Workspace(input, input, traces, true),
                new Workspace(input, input.resolve("child"), traces, true),
                new Workspace(input, output, output.resolve("child"), true),
                new Workspace(input, output, input.resolve("child"), true))) {
            assertEquals("WORKSPACE_INVALID", assertThrows(WorkspaceException.class,
                    () -> new WorkspaceSession(workspace, UUID.randomUUID())).code());
            assertFalse(Files.exists(input));
            assertFalse(Files.exists(output));
            assertFalse(Files.exists(traces));
        }
    }
}
