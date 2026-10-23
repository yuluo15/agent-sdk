package com.agent.agentcorelab.workspace;

import com.agent.agentcorelab.sample.analysis.SampleRun;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class WorkspaceSessionTests {
    @TempDir Path root;

    @Test void inputMustExistAndOutputAndTracesCanBeCreated() throws Exception {
        Workspace missing = new Workspace(root.resolve("missing"), root.resolve("out"), root.resolve("traces"));
        assertEquals("WORKSPACE_INVALID", assertThrows(WorkspaceException.class,
                () -> new WorkspaceSession(missing, UUID.randomUUID())).code());
        assertFalse(Files.exists(missing.outputRoot()));
        Files.createDirectory(missing.inputRoot());
        try (WorkspaceSession ignored = new WorkspaceSession(missing, UUID.randomUUID())) {
            assertTrue(Files.isDirectory(missing.outputRoot()));
            assertTrue(Files.isDirectory(missing.traceRoot()));
        }
    }

    @Test void allRootPairsRejectEqualityAndNesting() throws Exception {
        Path input = Files.createDirectory(root.resolve("in"));
        Path out = root.resolve("out");
        Path traces = root.resolve("traces");
        for (Workspace workspace : List.of(new Workspace(input, input, traces),
                new Workspace(input, input.resolve("nested"), traces), new Workspace(input, root, traces),
                new Workspace(input, out, out.resolve("nested")), new Workspace(input, traces.resolve("nested"), traces),
                new Workspace(input, out, input.resolve("nested")), new Workspace(input, out, root))) {
            assertEquals("WORKSPACE_INVALID", assertThrows(WorkspaceException.class,
                    () -> new WorkspaceSession(workspace, UUID.randomUUID())).code());
        }
    }

    @Test void opaqueIdsAreStableWithinRunAndUselessAcrossRunsOrAsPaths() throws Exception {
        try (SampleRun run = new SampleRun(root, false)) {
            Files.writeString(run.workspace.inputRoot().resolve("b.csv"), "name\na\n");
            Files.writeString(run.workspace.inputRoot().resolve("a.txt"), "notes");
            Files.writeString(run.workspace.inputRoot().resolve("ignored.json"), "{}");
            Files.createDirectory(run.workspace.inputRoot().resolve("nested"));
            Files.writeString(run.workspace.inputRoot().resolve("nested/hidden.txt"), "hidden");
            List<WorkspaceSession.FileEntry> first = run.session.listFiles(() -> { });
            assertEquals(List.of("a.txt", "b.csv"), first.stream().map(WorkspaceSession.FileEntry::name).toList());
            assertEquals(first, run.session.listFiles(() -> { }));
            try (WorkspaceSession second = new WorkspaceSession(run.workspace, UUID.randomUUID())) {
                assertEquals("UNKNOWN_FILE_ID", assertThrows(WorkspaceException.class, () -> second.read(first.getFirst().fileId())).code());
                assertNotEquals(first.getFirst().fileId(), second.listFiles(() -> { }).getFirst().fileId());
            }
            for (String fake : List.of("../a.txt", "C:/Windows/win.ini", "\\\\server\\share\\secret", "f_" + UUID.randomUUID())) {
                assertEquals("UNKNOWN_FILE_ID", assertThrows(WorkspaceException.class, () -> run.session.read(fake)).code());
            }
        }
    }

    @Test void readsVerifyDigestOfTheSameByteSnapshotAndDetectMissingFiles() throws Exception {
        try (SampleRun run = new SampleRun(root, false)) {
            Path file = run.workspace.inputRoot().resolve("source.txt");
            Files.writeString(file, "one");
            WorkspaceSession.FileEntry entry = run.session.listFiles(() -> { }).getFirst();
            WorkspaceSession.Snapshot snapshot = run.session.read(entry.fileId());
            assertEquals(entry.sha256(), WorkspaceSession.digest(snapshot.bytes()));
            byte[] copy = snapshot.bytes();
            copy[0] = 0;
            assertEquals("one", snapshot.utf8());
            Files.writeString(file, "two");
            WorkspaceException changed = assertThrows(WorkspaceException.class, () -> run.session.read(entry.fileId()));
            assertEquals("INPUT_CHANGED", changed.code());
            assertTrue(changed.terminal());
            assertEquals("INPUT_CHANGED", assertThrows(WorkspaceException.class, () -> run.session.listFiles(() -> { })).code());
            Files.delete(file);
            assertEquals("INPUT_CHANGED", assertThrows(WorkspaceException.class, () -> run.session.read(entry.fileId())).code());
        }
    }

    @Test void oversizedFileAndTooManyCandidatesFailRatherThanDisappear() throws Exception {
        try (SampleRun run = new SampleRun(root, false)) {
            Path large = run.workspace.inputRoot().resolve("large.txt");
            Files.write(large, new byte[WorkspaceSession.MAX_FILE_BYTES + 1]);
            assertEquals("FILE_LIMIT", assertThrows(WorkspaceException.class, () -> run.session.listFiles(() -> { })).code());
            Files.delete(large);
            for (int i = 0; i < 21; i++) Files.writeString(run.workspace.inputRoot().resolve("f" + i + ".txt"), "x");
            assertEquals("FILE_LIMIT", assertThrows(WorkspaceException.class, () -> run.session.listFiles(() -> { })).code());
        }
    }

    @Test void symlinksAndLinkedAncestorsAreRejectedWhenSupported() throws Exception {
        Path outside = Files.createDirectory(root.resolve("outside"));
        Path actual = Files.writeString(outside.resolve("secret.txt"), "not input");
        Path input = Files.createDirectory(root.resolve("input"));
        Path link = input.resolve("link.txt");
        createLinkOrSkip(link, actual);
        Workspace workspace = new Workspace(input, root.resolve("out"), root.resolve("traces"));
        try (WorkspaceSession session = new WorkspaceSession(workspace, UUID.randomUUID())) {
            assertEquals("PATH_NOT_ALLOWED", assertThrows(WorkspaceException.class, () -> session.listFiles(() -> { })).code());
        }
        Path linkedRoot = root.resolve("linked-root");
        createLinkOrSkip(linkedRoot, outside);
        assertEquals("PATH_NOT_ALLOWED", assertThrows(WorkspaceException.class,
                () -> new WorkspaceSession(new Workspace(linkedRoot, root.resolve("out2"), root.resolve("traces2")), UUID.randomUUID())).code());
        assertEquals("PATH_NOT_ALLOWED", assertThrows(WorkspaceException.class,
                () -> new WorkspaceSession(new Workspace(input, linkedRoot.resolve("child"), root.resolve("traces2")), UUID.randomUUID())).code());
    }

    @Test void windowsJunctionIsRejectedWithoutPretendingToSandboxConcurrentProcesses() throws Exception {
        assumeTrue(System.getProperty("os.name").toLowerCase().contains("windows"));
        Path outside = Files.createDirectory(root.resolve("junction-target"));
        Path junction = root.resolve("junction");
        Process process = new ProcessBuilder("cmd.exe", "/c", "mklink", "/J", junction.toString(), outside.toString())
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        assumeTrue(process.waitFor() == 0, "Junction creation is not permitted on this filesystem");
        try {
            for (boolean createInput : List.of(false, true)) {
                assertEquals("PATH_NOT_ALLOWED", assertThrows(WorkspaceException.class,
                        () -> new WorkspaceSession(new Workspace(junction, root.resolve("out"), root.resolve("traces"), createInput), UUID.randomUUID())).code());
                assertEquals("PATH_NOT_ALLOWED", assertThrows(WorkspaceException.class,
                        () -> new WorkspaceSession(new Workspace(junction.resolve("missing"), root.resolve("out"), root.resolve("traces"), createInput), UUID.randomUUID())).code());
            }
            Path input = root.resolve("new-input");
            assertEquals("PATH_NOT_ALLOWED", assertThrows(WorkspaceException.class,
                    () -> new WorkspaceSession(new Workspace(input, junction.resolve("out"), root.resolve("traces"), true), UUID.randomUUID())).code());
            assertFalse(Files.exists(input));
            assertFalse(Files.exists(outside.resolve("missing")));
            assertFalse(Files.exists(outside.resolve("out")));
        } finally {
            Files.deleteIfExists(junction);
        }
    }

    @Test void noReusingRunDirectoryAndCloseOnlyReleasesMemory() throws Exception {
        Artifact artifact;
        WorkspaceSession session;
        String evidence;
        try (SampleRun run = new SampleRun(root, true)) {
            session = run.session;
            List<String> ids = run.prepareReport();
            evidence = ids.getFirst();
            run.success("writeReport", run.report("真实工具生成的报告", ids.toArray(String[]::new)));
            artifact = run.artifacts.getLast();
            assertThrows(IOException.class, () -> new WorkspaceSession(run.workspace, run.runId));
        }
        assertTrue(Files.isRegularFile(artifact.path()));
        WorkspaceSession.verifyArtifact(artifact);
        assertNull(session.latestArtifact());
        assertEquals("SESSION_CLOSED", assertThrows(WorkspaceException.class, () -> session.evidence(evidence)).code());
    }

    @Test void evidenceSnapshotsAreImmutableAndBounded() throws Exception {
        try (SampleRun run = new SampleRun(root, false)) {
            ObjectNode data = WorkspaceJson.object().put("text", "before");
            String output = run.session.observe("test", WorkspaceJson.object(), data, List.of());
            String id = WorkspaceJson.parse(output).get("evidenceId").asText();
            data.put("text", "after");
            ((ObjectNode) run.session.evidence(id).data()).put("text", "mutated");
            assertEquals("before", run.session.evidence(id).data().get("text").asText());
            assertEquals("RESULT_LIMIT", assertThrows(WorkspaceException.class,
                    () -> run.session.observe("test", WorkspaceJson.object(), WorkspaceJson.object().put("large", "x".repeat(16384)), List.of())).code());
        }
    }

    private static void createLinkOrSkip(Path link, Path target) throws IOException {
        try { Files.createSymbolicLink(link, target); }
        catch (IOException | UnsupportedOperationException | SecurityException e) {
            assumeTrue(false, "Symbolic link creation is unavailable: " + e.getClass().getSimpleName());
        }
    }
}
