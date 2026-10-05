package com.forgeoj.worker.sandbox;

public record DockerCommandResult(
        int exitCode,
        String stdout,
        String stderr,
        boolean timedOut,
        boolean outputTruncated,
        boolean stdoutUtf8Valid) {
    public DockerCommandResult(int exitCode,String stdout,String stderr,boolean timedOut,boolean outputTruncated) {
        this(exitCode,stdout,stderr,timedOut,outputTruncated,true);
    }
}
