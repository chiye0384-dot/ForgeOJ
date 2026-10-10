/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.worker.testinfra;

import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

public final class ContentTestSchema {
    private ContentTestSchema() {}
    public static void afterV5(Connection connection,Path migrations) {
        for(String name:List.of("V6__ordinary_accounts.sql","V7__public_problem_library.sql","V8__personal_learning_records.sql","V9__authored_problem_drafts.sql","V10__official_solution_access.sql","V11__content_validation_jobs.sql","V12__immutable_content_reviews.sql","V13__content_output_previews.sql","V14__independent_self_test.sql","V15__classrooms_and_members.sql","V16__classroom_private_problems.sql","V17__classroom_assignments.sql","V18__independent_admin_identity.sql","V19__public_review_governance.sql","V20__bounded_operations_recovery.sql","V21__operations_recovery_receipts.sql","V22__restrict_operations_attempt_reads.sql")) {
            ScriptUtils.executeSqlScript(connection,new FileSystemResource(migrations.resolve(name)));
        }
        ScriptUtils.executeSqlScript(connection,new FileSystemResource(migrations.resolve("V23__reliable_redis_invalidation.sql")));
    }
}
