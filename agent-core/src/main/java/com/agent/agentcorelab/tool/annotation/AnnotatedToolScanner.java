package com.agent.agentcorelab.tool.annotation;

import com.agent.agentcorelab.tool.CandidateTool;
import com.agent.agentcorelab.tool.Tool;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Scans objects for {@link AgentTool} annotated methods and produces deterministic, validated {@link Tool} instances.
 */
public final class AnnotatedToolScanner {

    private AnnotatedToolScanner() {
    }

    /**
     * Scans the target object for methods annotated with {@link AgentTool}.
     *
     * @param target the instance containing tool methods
     * @return an unmodifiable list of tools, sorted alphabetically by tool name
     */
    public static List<Tool> scan(Object target) {
        Objects.requireNonNull(target, "target");
        List<Tool> tools = new ArrayList<>();
        Set<String> toolNames = new HashSet<>();

        Method[] methods = target.getClass().getMethods();
        for (Method method : methods) {
            if (method.isAnnotationPresent(AgentTool.class)) {
                AnnotatedTool tool = new AnnotatedTool(target, method);
                String name = tool.definition().name();
                if (!toolNames.add(name)) {
                    throw new IllegalArgumentException("Duplicate tool name '" + name + "' found in " + target.getClass().getName());
                }
                tools.add(tool);
            }
        }

        tools.sort(Comparator.comparing(t -> t.definition().name()));
        return List.copyOf(tools);
    }

    /**
     * Scans multiple target objects for {@link AgentTool} annotated methods.
     *
     * @param targets the target instances
     * @return an unmodifiable list of tools, sorted alphabetically by tool name
     */
    public static List<Tool> scanAll(Object... targets) {
        return scanAll(Arrays.asList(targets));
    }

    /**
     * Scans multiple target objects for {@link AgentTool} annotated methods.
     *
     * @param targets the collection of target instances
     * @return an unmodifiable list of tools, sorted alphabetically by tool name
     */
    public static List<Tool> scanAll(Collection<?> targets) {
        if (targets == null || targets.isEmpty()) {
            return List.of();
        }
        List<Tool> combined = new ArrayList<>();
        Set<String> toolNames = new HashSet<>();

        for (Object target : targets) {
            if (target == null) continue;
            List<Tool> scanned = scan(target);
            for (Tool tool : scanned) {
                String name = tool.definition().name();
                if (!toolNames.add(name)) {
                    throw new IllegalArgumentException("Duplicate tool name '" + name + "' across scanned targets");
                }
                combined.add(tool);
            }
        }

        combined.sort(Comparator.comparing(t -> t.definition().name()));
        return List.copyOf(combined);
    }

    /**
     * Scans target and returns candidate tool descriptors with metadata for pool registration.
     */
    public static List<CandidateTool> scanCandidates(Object target) {
        return scan(target).stream()
                .filter(AnnotatedTool.class::isInstance)
                .map(t -> ((AnnotatedTool) t).toCandidate())
                .toList();
    }

    /**
     * Scans multiple targets and returns candidate tool descriptors for pool registration.
     */
    public static List<CandidateTool> scanAllCandidates(Object... targets) {
        return scanAll(targets).stream()
                .filter(AnnotatedTool.class::isInstance)
                .map(t -> ((AnnotatedTool) t).toCandidate())
                .toList();
    }
}
