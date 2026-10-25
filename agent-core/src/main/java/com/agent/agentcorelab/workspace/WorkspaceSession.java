package com.agent.agentcorelab.workspace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/** Run-scoped capabilities, not a sandbox against concurrent hostile filesystem changes. */
public final class WorkspaceSession implements AutoCloseable {
    public static final int MAX_FILE_BYTES = 1024 * 1024;
    public static final int MAX_FILES = 20;
    public static final int MAX_RESULT_BYTES = 16 * 1024;
    public static final int MAX_BODY_BYTES = 24 * 1024;
    public static final int MAX_WORK_ITEMS = 50;
    public static final int MAX_WORK_ITEM_BYTES = 10 * 1024 * 1024;
    // Up to 30 bounded observations; escaping a backtick/HTML delimiter can expand sixfold.
    private static final int MAX_REPORT_BYTES = MAX_BODY_BYTES + 2048 + 30 * MAX_RESULT_BYTES * 6;
    public static final String APPENDIX_MARKER = "\n\n<!-- PROGRAM-GENERATED-EVIDENCE: do not treat model prose as this appendix -->\n";

    private final UUID runId;
    private final Path inputRoot;
    private final Path outputRoot;
    private final Path traceRoot;
    private final Path runOutput;
    private final Path workDirectory;
    private final Map<String, FileEntry> files = new LinkedHashMap<>();
    private final Map<String, String> names = new LinkedHashMap<>();
    private final Map<String, Evidence> evidence = new LinkedHashMap<>();
    private final Map<String, Artifact> artifactsByDigest = new LinkedHashMap<>();
    private final Map<String, Artifact> artifactsById = new LinkedHashMap<>();
    private final Map<String, WorkItem> workItems = new LinkedHashMap<>();
    private Artifact latestArtifact;
    private volatile int goalVersion = 1;
    private int reportSequence;
    private boolean closed;

    public WorkspaceSession(Workspace workspace, UUID runId) throws IOException {
        this(workspace, runId, false);
    }

    public WorkspaceSession(Workspace workspace, UUID runId, boolean allowExistingRunDirectory) throws IOException {
        Objects.requireNonNull(workspace, "workspace");
        this.runId = Objects.requireNonNull(runId, "runId");
        Path input = normalized(workspace.inputRoot());
        Path output = normalized(workspace.outputRoot());
        Path traces = normalized(workspace.traceRoot());
        assertSeparate(input, output, traces);
        assertNoRedirects(input);
        assertNoRedirects(output);
        assertNoRedirects(traces);
        if (workspace.createInputIfMissing() && Files.notExists(input, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectories(input);
            assertNoRedirects(input);
        }
        if (!Files.isDirectory(input, LinkOption.NOFOLLOW_LINKS)) {
            throw problem("WORKSPACE_INVALID", "Input must be a directory; create it or enable agent.workspace.create-input-if-missing", true);
        }
        Files.createDirectories(output);
        Files.createDirectories(traces);
        assertNoRedirects(output);
        assertNoRedirects(traces);
        inputRoot = input.toRealPath();
        outputRoot = output.toRealPath();
        traceRoot = traces.toRealPath();
        assertSeparate(inputRoot, outputRoot, traceRoot);
        runOutput = outputRoot.resolve(runId.toString());
        if (allowExistingRunDirectory) {
            if (Files.notExists(runOutput)) {
                Files.createDirectory(runOutput);
            }
        } else {
            // Never reuse a previous run directory, including one left after a crash.
            Files.createDirectory(runOutput);
        }
        Path workBase = outputRoot.getParent() != null ? outputRoot.getParent().resolve("work") : outputRoot.resolveSibling("work");
        workDirectory = workBase.resolve(runId.toString());
    }

    /**
     * Rehydrates an existing workspace session during checkpoint recovery or process restart.
     */
    public static WorkspaceSession restore(Workspace workspace, UUID runId) throws IOException {
        return new WorkspaceSession(workspace, runId, true);
    }

    public UUID runId() { return runId; }

    public record WorkItem(String itemId, String tool, String callId, Path path, long bytes, String summary, String preview) {
        public ObjectNode toJson() {
            return WorkspaceJson.object().put("itemId", itemId).put("tool", tool)
                    .put("callId", callId).put("bytes", bytes).put("summary", summary)
                    .put("preview", preview);
        }
    }

    public record WorkSlice(String itemId, int startLine, int lineCount, int totalLines, boolean hasMore, int nextLine, String content) {
        public ObjectNode toJson() {
            return WorkspaceJson.object().put("itemId", itemId).put("startLine", startLine)
                    .put("lineCount", lineCount).put("totalLines", totalLines).put("hasMore", hasMore)
                    .put("nextLine", nextLine).put("content", content);
        }
    }

    public record FileEntry(String fileId, String name, String type, long bytes, String sha256) {
        public ObjectNode toJson() {
            return WorkspaceJson.object().put("fileId", fileId).put("name", name)
                    .put("type", type).put("bytes", bytes).put("sha256", sha256);
        }
    }

    public record Snapshot(FileEntry file, byte[] bytes) {
        public Snapshot { bytes = bytes.clone(); }
        @Override public byte[] bytes() { return bytes.clone(); }

        public String utf8() throws WorkspaceException {
            try {
                String text = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes)).toString();
                return text.startsWith("\uFEFF") ? text.substring(1) : text;
            } catch (CharacterCodingException e) {
                throw problem("INVALID_ENCODING", "Only well-formed UTF-8 input is supported", false);
            }
        }
    }

    public record Evidence(String id, String tool, JsonNode sources, JsonNode data, List<FileEntry> files) {
        public Evidence {
            sources = sources.deepCopy();
            data = data.deepCopy();
            files = List.copyOf(files);
        }
        @Override public JsonNode sources() { return sources.deepCopy(); }
        @Override public JsonNode data() { return data.deepCopy(); }
        public ObjectNode toJson() {
            ObjectNode node = WorkspaceJson.object().put("evidenceId", id).put("tool", tool);
            node.set("sources", sources());
            node.set("data", data());
            ArrayNode refs = node.putArray("files");
            files.forEach(file -> refs.add(file.toJson()));
            return node;
        }
    }

    public synchronized List<FileEntry> listFiles(Runnable checkActive) throws IOException {
        ensureOpen();
        verifyRoots();
        checkActive.run();
        List<Path> candidates = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(inputRoot)) {
            for (Path path : stream) {
                checkActive.run();
                assertNoRedirects(path);
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) continue;
                if (typeOf(path.getFileName().toString()) == null) continue;
                if (candidates.size() == MAX_FILES) {
                    throw problem("FILE_LIMIT", "Input supports at most 20 candidate files", false);
                }
                candidates.add(path);
            }
        }
        candidates.sort(Comparator.comparing(path -> path.getFileName().toString()));
        // Validate previously discovered files too, including removed files.
        for (FileEntry file : files.values()) {
            checkActive.run();
            read(file.fileId());
        }
        List<FileEntry> result = new ArrayList<>();
        for (Path path : candidates) {
            checkActive.run();
            String name = path.getFileName().toString();
            if (names.containsKey(name)) {
                result.add(files.get(names.get(name)));
                continue;
            }
            byte[] bytes = readBytes(path);
            FileEntry file = new FileEntry("f_" + UUID.randomUUID(), name, typeOf(name), bytes.length, digest(bytes));
            result.add(file);
        }
        if (files.size() + result.stream().filter(file -> !files.containsKey(file.fileId())).count() > MAX_FILES) {
            throw problem("FILE_LIMIT", "A run supports at most 20 discovered files", false);
        }
        for (FileEntry file : result) {
            files.put(file.fileId(), file);
            names.put(file.name(), file.fileId());
        }
        return List.copyOf(result);
    }

    public synchronized FileEntry file(String fileId) throws WorkspaceException {
        ensureOpen();
        FileEntry result = files.get(fileId);
        if (result == null) throw problem("UNKNOWN_FILE_ID", "Use a fileId discovered in this run", false);
        return result;
    }

    public synchronized Snapshot read(String fileId) throws IOException {
        FileEntry file = file(fileId);
        verifyRoots();
        byte[] bytes;
        try {
            Path path = inputRoot.resolve(file.name());
            if (Files.notExists(path, LinkOption.NOFOLLOW_LINKS)) {
                throw problem("INPUT_CHANGED", "A discovered input is missing; start a new run", true);
            }
            bytes = readBytes(path);
        } catch (WorkspaceException e) {
            if (e.code().equals("PATH_NOT_ALLOWED")) throw e;
            throw problem("INPUT_CHANGED", "A discovered input is no longer the same bounded file; start a new run", true);
        } catch (IOException e) {
            throw problem("INPUT_CHANGED", "A discovered input is no longer readable; start a new run", true);
        }
        if (!file.sha256().equals(digest(bytes))) {
            throw problem("INPUT_CHANGED", "Input content changed; confirm the files and start a new run", true);
        }
        return new Snapshot(file, bytes);
    }

    public synchronized void verifyInputs(Runnable checkActive) throws IOException {
        ensureOpen();
        for (String id : files.keySet()) {
            checkActive.run();
            read(id);
        }
    }

    /** Stores only evidence whose entire successful observation fits the result budget. */
    public synchronized String observe(String tool, JsonNode sources, JsonNode data, List<String> fileIds)
            throws IOException {
        ensureOpen();
        List<FileEntry> refs = new ArrayList<>();
        for (String id : new TreeSet<>(fileIds)) refs.add(file(id));
        Evidence item = new Evidence("e_" + UUID.randomUUID(), tool, sources, data, refs);
        ObjectNode result = item.toJson().put("ok", true).put("runId", runId.toString());
        String json = WorkspaceJson.encode(result);
        if (WorkspaceJson.byteLength(json) > MAX_RESULT_BYTES) {
            throw problem("RESULT_LIMIT", "Structured result exceeds 16 KiB; use a narrower selection", false);
        }
        evidence.put(item.id(), item);
        return json;
    }

    /** Allows line-oriented readers to paginate without registering failed/partial observations. */
    public synchronized boolean observationFits(String tool, JsonNode sources, JsonNode data, List<String> fileIds)
            throws IOException {
        ensureOpen();
        List<FileEntry> refs = new ArrayList<>();
        for (String id : new TreeSet<>(fileIds)) refs.add(file(id));
        Evidence item = new Evidence("e_00000000-0000-0000-0000-000000000000", tool, sources, data, refs);
        String json = WorkspaceJson.encode(item.toJson().put("ok", true).put("runId", runId.toString()));
        return WorkspaceJson.byteLength(json) <= MAX_RESULT_BYTES;
    }

    public synchronized Evidence evidence(String id) throws WorkspaceException {
        ensureOpen();
        Evidence item = evidence.get(id);
        if (item == null) throw problem("UNKNOWN_EVIDENCE_ID", "Evidence must originate in this run", false);
        return item;
    }

    public synchronized List<Evidence> evidence(List<String> ids) throws WorkspaceException {
        List<Evidence> result = new ArrayList<>();
        for (String id : new TreeSet<>(ids)) result.add(evidence(id));
        return List.copyOf(result);
    }

    public synchronized Artifact latestArtifact() { return latestArtifact; }

    public synchronized void setGoalVersion(int version) {
        if (version > 0) {
            this.goalVersion = version;
        }
    }

    public int goalVersion() {
        return goalVersion;
    }

    public synchronized Optional<Artifact> artifact(String id) {
        return Optional.ofNullable(artifactsById.get(id));
    }

    public synchronized List<Artifact> allArtifacts() {
        return List.copyOf(artifactsById.values());
    }

    public synchronized Set<String> knownEvidenceIds() {
        Set<String> ids = new LinkedHashSet<>(evidence.keySet());
        ids.addAll(workItems.keySet());
        ids.addAll(artifactsById.keySet());
        return ids;
    }

    // Cancellation after CREATE_NEW must not hide a completed artifact.
    public synchronized Artifact writeReport(String title, String body, List<String> evidenceIds,
                                              Runnable checkActive) throws IOException {
        ensureOpen();
        if (title == null || title.isBlank() || title.length() > 200 || title.contains("\n") || title.contains("\r")
                || body == null || body.isBlank() || WorkspaceJson.byteLength(body) > MAX_BODY_BYTES) {
            throw problem("INVALID_ARGUMENT", "Provide a one-line title and nonempty body of at most 24 KiB", false);
        }
        if (evidenceIds == null || evidenceIds.isEmpty() || evidenceIds.size() > 30) {
            throw problem("INVALID_ARGUMENT", "Provide between 1 and 30 evidence references", false);
        }
        List<Evidence> refs = evidence(evidenceIds);
        checkActive.run();
        verifyInputs(checkActive);
        verifyRoots();
        assertNoRedirects(runOutput);
        byte[] content = renderReport(title, body, refs).getBytes(StandardCharsets.UTF_8);
        String hash = digest(content);
        Artifact existing = artifactsByDigest.get(hash);
        if (existing != null) {
            verifyArtifact(existing);
            checkActive.run();
            latestArtifact = existing;
            return existing;
        }
        Path target = runOutput.resolve(String.format(Locale.ROOT, "report-%03d.md", ++reportSequence));
        boolean created = false;
        try {
            checkActive.run();
            try (var output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                created = true;
                output.write(content);
            }
        } catch (IOException | RuntimeException e) {
            if (created) {
                try { Files.deleteIfExists(target); } catch (IOException cleanup) { e.addSuppressed(cleanup); }
            }
            throw e;
        }
        Artifact artifact = new Artifact("a_" + UUID.randomUUID(), target, hash,
                refs.stream().map(Evidence::id).toList(), goalVersion);
        artifactsByDigest.put(hash, artifact);
        artifactsById.put(artifact.id(), artifact);
        latestArtifact = artifact;
        return artifact;
    }

    public synchronized WorkItem saveWorkItem(String tool, String callId, String rawContent) throws IOException {
        ensureOpen();
        if (workItems.size() >= MAX_WORK_ITEMS) {
            throw problem("WORK_ITEM_LIMIT", "Run exceeded maximum work items (" + MAX_WORK_ITEMS + ")", false);
        }
        byte[] bytes = rawContent.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_WORK_ITEM_BYTES) {
            throw problem("WORK_ITEM_TOO_LARGE", "Tool output exceeds absolute storage limit (" + MAX_WORK_ITEM_BYTES + " bytes)", false);
        }
        if (Files.notExists(workDirectory)) {
            Files.createDirectories(workDirectory);
        }
        String itemId = "item_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        Path itemPath = workDirectory.resolve(itemId + ".json");
        Files.write(itemPath, bytes, StandardOpenOption.CREATE_NEW);

        String preview = rawContent.length() > 500 ? rawContent.substring(0, 500) + "..." : rawContent;
        String summary = String.format(Locale.ROOT, "Tool '%s' result (%d bytes) offloaded to workspace storage item '%s'.", tool, bytes.length, itemId);
        WorkItem item = new WorkItem(itemId, tool, callId, itemPath, bytes.length, summary, preview);
        workItems.put(itemId, item);
        return item;
    }

    public synchronized WorkItem workItem(String itemId) throws WorkspaceException {
        ensureOpen();
        WorkItem item = workItems.get(itemId);
        if (item == null) {
            throw problem("UNKNOWN_WORK_ITEM", "Unknown work item ID: " + itemId, false);
        }
        return item;
    }

    public synchronized WorkSlice readWorkItemSlice(String itemId, int startLine, int maxLines) throws IOException {
        ensureOpen();
        WorkItem item = workItem(itemId);
        List<String> allLines = Files.readAllLines(item.path(), StandardCharsets.UTF_8);
        int totalLines = allLines.size();
        int actualStart = Math.max(1, startLine);
        int boundedMaxLines = Math.min(100, Math.max(1, maxLines));
        int fromIndex = Math.min(actualStart - 1, totalLines);
        int toIndex = Math.min(fromIndex + boundedMaxLines, totalLines);
        List<String> slice = allLines.subList(fromIndex, toIndex);
        boolean hasMore = toIndex < totalLines;
        int nextLine = hasMore ? toIndex + 1 : -1;
        return new WorkSlice(itemId, actualStart, slice.size(), totalLines, hasMore, nextLine, String.join("\n", slice));
    }

    public record DeliverableCheckResult(boolean passed, String artifactId, int goalVersion, List<String> issues) {
        public DeliverableCheckResult {
            issues = List.copyOf(issues != null ? issues : List.of());
        }
    }

    public synchronized DeliverableCheckResult checkDeliverable(String artifactId) {
        if (closed) {
            return new DeliverableCheckResult(false, artifactId, this.goalVersion,
                    List.of("SESSION_CLOSED: Workspace session is closed"));
        }
        List<String> issues = new ArrayList<>();
        Artifact artifact = artifactsById.get(artifactId);
        if (artifact == null) {
            issues.add("ARTIFACT_NOT_FOUND: Artifact '" + artifactId + "' does not exist");
            return new DeliverableCheckResult(false, artifactId, this.goalVersion, issues);
        }
        try {
            verifyArtifact(artifact);
        } catch (Exception e) {
            issues.add("INVALID_ARTIFACT_FILE: " + e.getMessage());
        }
        if (artifact.goalVersion() != this.goalVersion) {
            issues.add("STALE_GOAL: Artifact was produced for goalVersion " + artifact.goalVersion()
                    + ", but current goalVersion is " + this.goalVersion);
        }
        for (String evidenceId : artifact.evidenceIds()) {
            if (!evidence.containsKey(evidenceId)) {
                issues.add("MISSING_EVIDENCE: Referenced evidence '" + evidenceId + "' does not exist in workspace session");
            }
        }
        return new DeliverableCheckResult(issues.isEmpty(), artifactId, artifact.goalVersion(), issues);
    }

    public synchronized WorkItem saveWorkNote(String title, String content, List<String> sourceRefs) throws IOException {
        ensureOpen();
        if (title == null || title.isBlank() || title.length() > 200) {
            throw problem("INVALID_NOTE_TITLE", "Note title must be between 1 and 200 characters", false);
        }
        if (content == null || content.isBlank() || content.length() > 16384) {
            throw problem("INVALID_NOTE_CONTENT", "Note content must be between 1 and 16384 characters", false);
        }
        List<String> validRefs = sourceRefs != null ? sourceRefs : List.of();
        Set<String> known = knownEvidenceIds();
        for (String ref : validRefs) {
            if (!known.contains(ref)) {
                throw problem("MISSING_EVIDENCE", "Referenced evidence or item '" + ref + "' does not exist", false);
            }
        }
        ObjectNode noteNode = WorkspaceJson.object()
                .put("title", title)
                .put("content", content)
                .put("type", "MODEL_DERIVED")
                .put("createdAt", Instant.now().toString());
        ArrayNode refsNode = WorkspaceJson.array();
        for (String ref : validRefs) {
            refsNode.add(ref);
        }
        noteNode.set("sourceRefs", refsNode);
        String noteJson = WorkspaceJson.encode(noteNode);
        return saveWorkItem("saveWorkNote", "note-" + UUID.randomUUID(), noteJson);
    }

    /** Deterministic appendix includes full structured analysis, not fabricated citations. */
    public static String renderReport(String title, String body, List<Evidence> refs) throws IOException {
        ArrayNode appendix = WorkspaceJson.array();
        refs.stream().sorted(Comparator.comparing(Evidence::id)).forEach(ref -> appendix.add(ref.toJson()));
        String json = WorkspaceJson.encode(WorkspaceJson.canonical(appendix));
        // Escape backticks and HTML delimiters so input text cannot terminate the appendix fence.
        json = json.replace("`", "\\u0060").replace("<", "\\u003c").replace(">", "\\u003e");
        return "# " + title + "\n\n" + body + APPENDIX_MARKER
                + "## Program-generated sources and exact analysis data\n\n```json\n" + json + "\n```\n";
    }

    public static void verifyArtifact(Artifact artifact) throws IOException {
        assertNoRedirects(artifact.path());
        if (!Files.isRegularFile(artifact.path(), LinkOption.NOFOLLOW_LINKS)
                || Files.size(artifact.path()) > MAX_REPORT_BYTES) {
            throw problem("ARTIFACT_CHANGED", "Report is missing or changed", true);
        }
        try (InputStream input = Files.newInputStream(artifact.path(), LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(MAX_REPORT_BYTES + 1);
            if (bytes.length > MAX_REPORT_BYTES || !digest(bytes).equals(artifact.contentDigest())) {
                throw problem("ARTIFACT_CHANGED", "Report content digest no longer matches", true);
            }
        }
    }

    public static String digest(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK must support SHA-256", e);
        }
    }

    /** Checks every existing ancestor, including Windows junction/other reparse entries. */
    public static void assertNoRedirects(Path path) throws IOException {
        Path absolute = normalized(path);
        Path current = absolute.getRoot();
        for (Path segment : absolute) {
            current = current.resolve(segment);
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.notExists(current, LinkOption.NOFOLLOW_LINKS)) continue;
                throw problem("PATH_NOT_ALLOWED", "Cannot verify workspace path", true);
            }
            BasicFileAttributes attrs = Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attrs.isSymbolicLink() || attrs.isOther()
                    || !current.toRealPath(LinkOption.NOFOLLOW_LINKS).equals(current.toRealPath())) {
                throw problem("PATH_NOT_ALLOWED", "Links and redirected paths are not supported", true);
            }
        }
    }

    private void verifyRoots() throws IOException {
        for (Path root : List.of(inputRoot, outputRoot, traceRoot)) {
            assertNoRedirects(root);
            if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
                throw problem("WORKSPACE_INVALID", "Workspace directory is missing", true);
            }
        }
    }

    private byte[] readBytes(Path path) throws IOException {
        assertNoRedirects(path);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw problem("PATH_NOT_ALLOWED", "Only regular input files are supported", true);
        }
        if (Files.size(path) > MAX_FILE_BYTES) throw problem("FILE_LIMIT", "Input file exceeds 1 MiB", false);
        try (InputStream input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(MAX_FILE_BYTES + 1);
            if (bytes.length > MAX_FILE_BYTES) throw problem("FILE_LIMIT", "Input file exceeds 1 MiB", false);
            return bytes;
        }
    }

    private static String typeOf(String name) {
        int dot = name.lastIndexOf('.');
        String extension = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
        return switch (extension) { case "csv", "txt", "md" -> extension; default -> null; };
    }

    private static Path normalized(Path path) { return Objects.requireNonNull(path, "workspace path").toAbsolutePath().normalize(); }

    private static void assertSeparate(Path... paths) throws WorkspaceException {
        for (int i = 0; i < paths.length; i++) {
            for (int j = i + 1; j < paths.length; j++) {
                if (paths[i].startsWith(paths[j]) || paths[j].startsWith(paths[i])) {
                    throw problem("WORKSPACE_INVALID", "Input, output and traces must not overlap", true);
                }
            }
        }
    }

    private void ensureOpen() throws WorkspaceException {
        if (closed) throw problem("SESSION_CLOSED", "Run resources have been released", true);
    }

    private static WorkspaceException problem(String code, String message, boolean terminal) {
        return new WorkspaceException(code, message, terminal);
    }

    public synchronized void cleanupTempFiles() {
        if (workDirectory != null && Files.isDirectory(workDirectory)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(workDirectory)) {
                for (Path entry : stream) {
                    try {
                        Files.deleteIfExists(entry);
                    } catch (IOException ignored) {
                    }
                }
                Files.deleteIfExists(workDirectory);
            } catch (IOException ignored) {
            }
        }
        workItems.clear();
    }

    @Override public synchronized void close() {
        cleanupTempFiles();
        files.clear();
        names.clear();
        evidence.clear();
        artifactsByDigest.clear();
        artifactsById.clear();
        workItems.clear();
        latestArtifact = null;
        closed = true;
    }
}
