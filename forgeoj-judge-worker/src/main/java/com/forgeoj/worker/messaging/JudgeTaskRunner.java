package com.forgeoj.worker.messaging;

@FunctionalInterface
public interface JudgeTaskRunner {

    void run(JudgeTaskMessage message);
}
