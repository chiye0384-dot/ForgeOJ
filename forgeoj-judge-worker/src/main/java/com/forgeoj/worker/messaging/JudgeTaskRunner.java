package com.forgeoj.worker.messaging;

import com.forgeoj.worker.task.ClaimedJudgeTask;

@FunctionalInterface
public interface JudgeTaskRunner {

    void run(ClaimedJudgeTask claimedTask);
}
