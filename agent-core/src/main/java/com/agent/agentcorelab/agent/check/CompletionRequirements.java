package com.agent.agentcorelab.agent.check;

import com.agent.agentcorelab.agent.plan.TaskBrief;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Immutable per-run acceptance contract defining required deliverables, evidence, criteria, and profiles.
 */
public record CompletionRequirements(
        int goalVersion,
        Set<CompletionProfile> profiles,
        List<Criterion> criteria,
        List<DeliverableRequirement> deliverables,
        EvidenceRequirement evidenceRequirement,
        ActionRequirement actionRequirement,
        Optional<CompletionJudge> judge
) {
    public CompletionRequirements {
        if (goalVersion <= 0) {
            goalVersion = 1;
        }
        profiles = Set.copyOf(profiles != null && !profiles.isEmpty() ? profiles : CompletionProfile.textOnly());
        criteria = List.copyOf(criteria != null ? criteria : List.of());
        deliverables = List.copyOf(deliverables != null ? deliverables : List.of());
        evidenceRequirement = evidenceRequirement != null ? evidenceRequirement : EvidenceRequirement.none();
        actionRequirement = actionRequirement != null ? actionRequirement : ActionRequirement.none();
        judge = judge != null ? judge : Optional.empty();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static CompletionRequirements textOnly() {
        return builder().profiles(CompletionProfile.textOnly()).build();
    }

    public static CompletionRequirements textWithEvidence(int minObservations, boolean mustCiteInAnswer) {
        return builder()
                .profiles(CompletionProfile.textWithEvidence())
                .evidenceRequirement(new EvidenceRequirement(mustCiteInAnswer, minObservations, mustCiteInAnswer))
                .build();
    }

    public static CompletionRequirements report(DeliverableRequirement... deliverables) {
        Builder builder = builder().profiles(CompletionProfile.fullReport());
        if (deliverables != null) {
            for (DeliverableRequirement d : deliverables) {
                builder.addDeliverable(d);
            }
        }
        return builder.build();
    }

    public static CompletionRequirements fromTaskBrief(TaskBrief brief) {
        if (brief == null) {
            return textOnly();
        }
        Builder builder = builder().goalVersion(brief.goalVersion());
        Set<CompletionProfile> profiles = new HashSet<>();
        profiles.add(CompletionProfile.TEXT);

        if (brief.deliverables() != null && !brief.deliverables().isEmpty()) {
            profiles.add(CompletionProfile.ARTIFACT);
            for (String d : brief.deliverables()) {
                DeliverableRole role = DeliverableRole.inferFromFileName(d);
                builder.addDeliverable(new DeliverableRequirement(d, role, "Deliverable: " + d));
            }
        }
        builder.profiles(profiles);
        return builder.build();
    }

    public Builder toBuilder() {
        Builder b = new Builder()
                .goalVersion(this.goalVersion)
                .profiles(this.profiles)
                .evidenceRequirement(this.evidenceRequirement)
                .actionRequirement(this.actionRequirement);
        this.judge.ifPresent(b::judge);
        this.criteria.forEach(b::addCriterion);
        this.deliverables.forEach(b::addDeliverable);
        return b;
    }

    public static final class Builder {
        private int goalVersion = 1;
        private Set<CompletionProfile> profiles = new HashSet<>(CompletionProfile.textOnly());
        private final List<Criterion> criteria = new ArrayList<>();
        private final List<DeliverableRequirement> deliverables = new ArrayList<>();
        private EvidenceRequirement evidenceRequirement = EvidenceRequirement.none();
        private ActionRequirement actionRequirement = ActionRequirement.none();
        private CompletionJudge judge;

        public Builder goalVersion(int goalVersion) {
            this.goalVersion = goalVersion;
            return this;
        }

        public Builder profiles(Set<CompletionProfile> profiles) {
            this.profiles = new HashSet<>(Objects.requireNonNull(profiles, "profiles"));
            return this;
        }

        public Builder addProfile(CompletionProfile profile) {
            this.profiles.add(Objects.requireNonNull(profile, "profile"));
            return this;
        }

        public Builder addCriterion(Criterion criterion) {
            this.criteria.add(Objects.requireNonNull(criterion, "criterion"));
            return this;
        }

        public Builder addDeliverable(DeliverableRequirement deliverable) {
            this.deliverables.add(Objects.requireNonNull(deliverable, "deliverable"));
            this.profiles.add(CompletionProfile.ARTIFACT);
            return this;
        }

        public Builder evidenceRequirement(EvidenceRequirement evidenceRequirement) {
            this.evidenceRequirement = Objects.requireNonNull(evidenceRequirement, "evidenceRequirement");
            if (evidenceRequirement.minObservations() > 0 || evidenceRequirement.requireCitations()) {
                this.profiles.add(CompletionProfile.EVIDENCE);
            }
            return this;
        }

        public Builder actionRequirement(ActionRequirement actionRequirement) {
            this.actionRequirement = Objects.requireNonNull(actionRequirement, "actionRequirement");
            if (actionRequirement.requireSuccessfulExecution() || actionRequirement.requireReceipt()) {
                this.profiles.add(CompletionProfile.ACTION);
            }
            return this;
        }

        public Builder judge(CompletionJudge judge) {
            this.judge = judge;
            return this;
        }

        public CompletionRequirements build() {
            return new CompletionRequirements(
                    goalVersion,
                    profiles,
                    criteria,
                    deliverables,
                    evidenceRequirement,
                    actionRequirement,
                    Optional.ofNullable(judge)
            );
        }
    }
}
