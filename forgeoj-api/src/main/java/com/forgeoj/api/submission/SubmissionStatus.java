package com.forgeoj.api.submission;

public record SubmissionStatus(
        String submissionId,
        String processingStatus,
        long statusVersion,
        String verdict,
        String diagnosticMessage,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) String judgeDataWarning) {
    @org.apache.ibatis.annotations.AutomapConstructor
    public SubmissionStatus {}
    public SubmissionStatus(String submissionId,String processingStatus,long statusVersion,String verdict,String diagnosticMessage){this(submissionId,processingStatus,statusVersion,verdict,diagnosticMessage,null);}
}
