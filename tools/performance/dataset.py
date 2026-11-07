#!/usr/bin/env python3
# Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
"""Original deterministic read/judge fixture. Only install into an owned empty test DB."""
import hashlib
import json


def fixture():
    literal = lambda text: "CONVERT(0x" + text.encode('utf-8').hex() + " USING utf8mb4)"
    statements = ['START TRANSACTION;']
    problems = []
    for index in range(300):
        identity = 1001 + index
        bucket = index % 3
        size = [256, 2048, 8192][bucket]
        slug = 'perf-problem-' + str(index + 1).zfill(3)
        title = 'PERF ' + str(index + 1).zfill(3) + ' sum integers'
        text = ('Original ForgeOJ benchmark: read two signed integers and print their sum. ' * 120)[:size]
        difficulty = ['EASY', 'MEDIUM', 'HARD'][bucket]
        tag = ['perf-short', 'perf-medium', 'perf-long'][bucket]
        statements.append("INSERT INTO problem(id,slug,title,statement_text,input_description,output_description,public_samples_json,status,current_judge_version_id,difficulty,created_at,updated_at) VALUES(" +
                          ','.join([str(identity), literal(slug), literal(title), literal(text), literal('Two signed 64-bit integers.'),
                                    literal('Their sum.'), "JSON_ARRAY(JSON_OBJECT('input','1 2','output','3'))", "'ACTIVE'", 'NULL', literal(difficulty), "'2026-01-01'", "'2026-01-01'"]) + ');')
        statements.append('INSERT INTO problem_judge_version(id,problem_id,version_no,time_limit_ms,memory_limit_mb,output_limit_bytes,comparison_rule_version,sandbox_policy_version,java_image_digest,test_dataset_sha256,created_at) SELECT ' +
                          str(identity) + ',' + str(identity) + ",version_no,time_limit_ms,memory_limit_mb,output_limit_bytes,comparison_rule_version,sandbox_policy_version,java_image_digest,test_dataset_sha256,'2026-01-01' FROM problem_judge_version WHERE id=1;")
        statements.append('INSERT INTO problem_test_case(id,judge_version_id,ordinal,input_data_gzip,expected_output_gzip,input_size_bytes,output_size_bytes,input_sha256,output_sha256) SELECT ' +
                          str(100000 + index*3) + '+ordinal,' + str(identity) + ',ordinal,input_data_gzip,expected_output_gzip,input_size_bytes,output_size_bytes,input_sha256,output_sha256 FROM problem_test_case WHERE judge_version_id=1;')
        statements.append('UPDATE problem SET current_judge_version_id=' + str(identity) + ",updated_at='2026-01-01' WHERE id=" + str(identity) + ';')
        statements.append('INSERT INTO problem_tag(problem_id,tag) VALUES(' + str(identity) + ',' + literal(tag) + '),(' + str(identity) + ",'sum-integers');")
        problems.append({'slug': slug, 'title': title, 'statementBytes': len(text.encode()), 'statementSha256': hashlib.sha256(text.encode()).hexdigest(), 'difficulty': difficulty, 'tag': tag})
    statements.append('COMMIT;')
    blueprint = {'version': 'forgeoj-read-judge-baseline-v1', 'publicProblems': 301, 'syntheticProblems': 300,
                 'users': 1, 'initialSubmissions': 0, 'classrooms': 0, 'problems': problems,
                 'source': 'original synthetic public fixture, not author workflow or real production content'}
    canonical = json.dumps(blueprint, sort_keys=True, separators=(',', ':')).encode()
    blueprint['sha256'] = hashlib.sha256(canonical).hexdigest()
    return '\n'.join(statements), blueprint
