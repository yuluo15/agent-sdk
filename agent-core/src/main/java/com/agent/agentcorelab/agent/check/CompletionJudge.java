package com.agent.agentcorelab.agent.check;

import com.agent.agentcorelab.agent.run.RunSnapshot;

import java.util.Objects;

/**
 * Optional semantic quality inspector SPI.
 * <p>
 * Evaluates semantic prose quality, tone, and subjective criteria.
 * <b>Important:</b> The judge can NEVER override deterministic hard check failures.
 */
@FunctionalInterface
public interface CompletionJudge {

    JudgeDecision evaluate(RunSnapshot run, String finalAnswer, CompletionRequirements requirements);

    record JudgeDecision(Status status, String feedback, double score) {
        public enum Status {
            PASS,
            FAIL,
            UNCERTAIN
        }

        public JudgeDecision {
            Objects.requireNonNull(status, "status");
            feedback = feedback != null ? feedback : "";
            score = Math.clamp(score, 0.0, 1.0);
        }

        public static JudgeDecision pass(String feedback) {
            return new JudgeDecision(Status.PASS, feedback, 1.0);
        }

        public static JudgeDecision fail(String feedback) {
            return new JudgeDecision(Status.FAIL, feedback, 0.0);
        }

        public static JudgeDecision uncertain(String feedback) {
            return new JudgeDecision(Status.UNCERTAIN, feedback, 0.5);
        }
    }
}
