# Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
"""Fixed SELECTs matching the public fixture parameters and existing Mapper contracts."""
def queries():
    base = """FROM problem p JOIN problem_judge_version jv ON jv.id=p.current_judge_version_id
WHERE p.status='ACTIVE' AND p.scope='PUBLIC' AND p.title LIKE '%PERF%' ESCAPE '='
AND p.difficulty='EASY' AND EXISTS(SELECT 1 FROM problem_tag pt WHERE pt.problem_id=p.id AND pt.tag='perf-short')"""
    return {
        'list-count': 'SELECT COUNT(*) ' + base,
        'list-page-first': 'SELECT p.id,p.slug,p.title,p.difficulty,jv.version_no AS judgeVersion ' + base + ' ORDER BY p.id ASC LIMIT 20 OFFSET 0',
        'list-page-last': 'SELECT p.id,p.slug,p.title,p.difficulty,jv.version_no AS judgeVersion ' + base + ' ORDER BY p.id ASC LIMIT 20 OFFSET 80',
        'list-tags': 'SELECT problem_id AS problemId,tag FROM problem_tag WHERE problem_id IN (' + ','.join(str(1001+3*i) for i in range(20)) + ') ORDER BY problem_id ASC,tag ASC',
        'detail-epoch': "SELECT revision FROM cache_epoch WHERE namespace='public'",
    }
