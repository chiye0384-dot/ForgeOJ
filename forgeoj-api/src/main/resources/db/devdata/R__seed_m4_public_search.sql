-- Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
-- Disposable development seed only; production migrations initialize existing PUBLIC rows.
-- Older target-version upgrade tests also load devdata. They must remain a no-op before V24.
SET @forgeoj_search_seed_ready = EXISTS(SELECT 1 FROM information_schema.tables
 WHERE table_schema=DATABASE() AND table_name='public_search_outbox');
START TRANSACTION;
SET @forgeoj_search_seed_sql=IF(@forgeoj_search_seed_ready,
 'UPDATE cache_epoch SET revision=revision+1 WHERE namespace=''public''','DO 0');
PREPARE forgeoj_search_seed FROM @forgeoj_search_seed_sql;
EXECUTE forgeoj_search_seed;
DEALLOCATE PREPARE forgeoj_search_seed;
SET @forgeoj_search_seed_sql=IF(@forgeoj_search_seed_ready,
 'INSERT IGNORE INTO public_search_version(problem_id,data_version) SELECT id,1 FROM problem WHERE scope=''PUBLIC''','DO 0');
PREPARE forgeoj_search_seed FROM @forgeoj_search_seed_sql;
EXECUTE forgeoj_search_seed;
DEALLOCATE PREPARE forgeoj_search_seed;
SET @forgeoj_search_seed_sql=IF(@forgeoj_search_seed_ready,
 'UPDATE public_search_version SET data_version=data_version+1','DO 0');
PREPARE forgeoj_search_seed FROM @forgeoj_search_seed_sql;
EXECUTE forgeoj_search_seed;
DEALLOCATE PREPARE forgeoj_search_seed;
SET @forgeoj_search_seed_sql=IF(@forgeoj_search_seed_ready,
 'INSERT INTO public_search_outbox(id,problem_id,data_version,public_epoch) SELECT UUID(),problem_id,data_version,(SELECT revision FROM cache_epoch WHERE namespace=''public'') FROM public_search_version','DO 0');
PREPARE forgeoj_search_seed FROM @forgeoj_search_seed_sql;
EXECUTE forgeoj_search_seed;
DEALLOCATE PREPARE forgeoj_search_seed;
COMMIT;
