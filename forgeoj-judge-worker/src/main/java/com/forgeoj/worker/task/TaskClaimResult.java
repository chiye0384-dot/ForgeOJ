package com.forgeoj.worker.task;

public record TaskClaimResult(TaskClaimOutcome outcome, ClaimedJudgeTask claimedTask) {

    public TaskClaimResult {
        if ((outcome == TaskClaimOutcome.CLAIMED) != (claimedTask != null)) {
            throw new IllegalArgumentException("Only a claimed result may carry execution ownership");
        }
    }

    static TaskClaimResult claimed(ClaimedJudgeTask task) {
        return new TaskClaimResult(TaskClaimOutcome.CLAIMED, task);
    }

    static TaskClaimResult withoutOwnership(TaskClaimOutcome outcome) {
        return new TaskClaimResult(outcome, null);
    }
}
