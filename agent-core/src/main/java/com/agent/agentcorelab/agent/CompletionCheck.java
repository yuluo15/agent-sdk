package com.agent.agentcorelab.agent;

import com.agent.agentcorelab.agent.run.RunSnapshot;

import java.util.Objects;

@FunctionalInterface
public interface CompletionCheck {

    Result check(RunSnapshot run, String finalAnswer);

    record Result(boolean complete, String feedback, Status status) {

        public enum Status {
            /** The deliverable and execution constraints are fully satisfied. */
            COMPLETE,
            /** The deliverable is incomplete and needs correction or further execution. */
            INCOMPLETE,
            /** The model response is an intermediate explanation/clarification, not a final deliverable attempt. */
            INTERMEDIATE_EXPLANATION
        }

        public Result {
            Objects.requireNonNull(feedback, "feedback");
            Objects.requireNonNull(status, "status");
        }

        public Result(boolean complete, String feedback) {
            this(complete, feedback, complete ? Status.COMPLETE : Status.INCOMPLETE);
        }

        public static Result satisfied() {
            return new Result(true, "", Status.COMPLETE);
        }

        public static Result incomplete(String feedback) {
            return new Result(false, feedback, Status.INCOMPLETE);
        }

        public static Result intermediateExplanation(String explanation) {
            return new Result(false, explanation, Status.INTERMEDIATE_EXPLANATION);
        }
    }
}
