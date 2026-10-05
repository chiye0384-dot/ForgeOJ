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
        for(String name:List.of("V6__ordinary_accounts.sql","V7__public_problem_library.sql","V8__personal_learning_records.sql","V9__authored_problem_drafts.sql","V10__official_solution_access.sql","V11__content_validation_jobs.sql","V12__immutable_content_reviews.sql","V13__content_output_previews.sql","V14__independent_self_test.sql")) {
            ScriptUtils.executeSqlScript(connection,new FileSystemResource(migrations.resolve(name)));
        }
    }
}
