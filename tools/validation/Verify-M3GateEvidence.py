# Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
"""Read-only M3 gate audit: saved real builds, runtime and redacted evidence.

Requires Python 3 standard library only. Never sends requests or loads credentials.
"""
import argparse
import hashlib
import json
import re
import subprocess
import xml.etree.ElementTree as ET
import zipfile
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
EVIDENCE = ROOT / 'docs/evidence'


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def load(path):
    return json.loads(path.read_text(encoding='utf-8-sig'))


def owned(path, prefix):
    resolved = (ROOT / path).resolve()
    require(resolved.is_relative_to(ROOT / 'target'), 'Build must be inside target')
    require(resolved.name.startswith(prefix), 'Unexpected build directory')
    return resolved


def manifest(path):
    result = {}
    for line in path.read_text(encoding='utf-8-sig').splitlines():
        match = re.fullmatch(r'([a-f0-9]{64})\s+(.+)', line)
        require(match, 'Invalid SHA manifest line')
        name = match[2].removeprefix('./').replace('\\', '/')
        require(name not in result, 'Duplicate manifest path')
        result[name] = match[1]
    return result


def inventory(directory):
    return sorted(p.relative_to(ROOT).as_posix() for p in (ROOT / directory).rglob('*')
                  if p.is_file() and not any(x in p.relative_to(ROOT / directory).parts
                                            for x in ('node_modules', 'dist', 'target', '.git', '.idea'))
                  and not p.name.endswith('.local') and not p.name.startswith('.env'))


def check_inputs(paths, frozen):
    for name in paths:
        require(frozen.get(name) == digest(ROOT / name), 'Untested input: ' + name)
    return len(paths)


def suites(build, module, expected):
    result = dict(suites=0, tests=0, failures=0, errors=0, skipped=0)
    names = set()
    for file in sorted((build / module / 'surefire-reports').glob('TEST-*.xml')):
        suite = ET.parse(file).getroot()
        result['suites'] += 1
        for key in ('tests', 'failures', 'errors', 'skipped'):
            result[key] += int(suite.attrib[key])
        for case in suite.findall('testcase'):
            require(not (case.findall('failure') + case.findall('error') + case.findall('skipped')),
                    'Nonpassing testcase: ' + case.attrib['name'])
            names.add(case.attrib['classname'].split('.')[-1] + '.' + case.attrib['name'])
    require(result['tests'] == expected and result['suites'] > 0, 'Unexpected test inventory')
    require(sum(result[k] for k in ('failures', 'errors', 'skipped')) == 0, 'Failed build')
    require('BUILD SUCCESS' in (build / 'backend.log').read_text(encoding='utf-8'), 'Missing Maven success')
    result['jarSha256'] = digest(build / module / (module + '-0.0.1-SNAPSHOT.jar'))
    return result, names


def jar_runtime(path):
    with zipfile.ZipFile(path) as jar:
        return {name: hashlib.sha256(jar.read(name)).hexdigest() for name in sorted(jar.namelist())
                if not name.endswith('/') and name.startswith(('BOOT-INF/classes/', 'BOOT-INF/lib/'))}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('api_build', help='Fresh test-only API build under target/forgeoj-linux-*')
    parser.add_argument('--cleanup', required=True, help='Fresh read-only Docker cleanup snapshot')
    args = parser.parse_args()
    api_build = owned(args.api_build, 'forgeoj-linux-')
    teacher = load(EVIDENCE / 'm3-teacher-records/verification.json')
    require(teacher['allPassed'] is True, 'Teacher unit not verified')
    runtime_build = owned(teacher['backend'].replace('\\', '/'), 'forgeoj-linux-')
    frontend_build = owned(teacher['frontend'].replace('\\', '/'), 'forgeoj-linux-')
    replay = owned(teacher['replay'].replace('\\', '/'), 'forgeoj-e2e-')
    new_manifest = manifest(api_build / 'source-files.sha256')
    runtime_manifest = manifest(runtime_build / 'source-files.sha256')
    front_manifest = manifest(frontend_build / 'source-files.sha256')
    live_front_manifest = manifest(replay / 'frontend-runtime.sha256')
    backend_paths = ['pom.xml', 'forgeoj-api/pom.xml', 'forgeoj-judge-worker/pom.xml']
    backend_paths += inventory('forgeoj-api/src') + inventory('forgeoj-judge-worker/src')
    backend_count = check_inputs(backend_paths, new_manifest)
    production_paths = ['pom.xml', 'forgeoj-api/pom.xml', 'forgeoj-judge-worker/pom.xml']
    production_paths += inventory('forgeoj-api/src/main') + inventory('forgeoj-judge-worker/src/main') + inventory('contracts')
    production_count = check_inputs(production_paths, runtime_manifest)
    check_inputs(inventory('contracts'), new_manifest)
    # Exact inventories prevent a newly added file from bypassing the old runtime proof.
    runtime_production = {p for p in runtime_manifest if p in production_paths or
                          p.startswith(('forgeoj-api/src/main/', 'forgeoj-judge-worker/src/main/', 'contracts/'))}
    require(runtime_production == set(production_paths), 'Runtime production inventory changed')
    worker_paths = ['pom.xml', 'forgeoj-judge-worker/pom.xml'] + inventory('forgeoj-judge-worker/src')
    worker_paths += inventory('forgeoj-api/src/main/resources/db') + inventory('contracts')
    worker_count = check_inputs(worker_paths, runtime_manifest)
    front_paths = inventory('frontend')
    frontend_count = check_inputs(front_paths, front_manifest)
    check_inputs(front_paths, live_front_manifest)
    require(set(front_paths) == set(live_front_manifest), 'Browser frontend inventory changed')
    executed_helpers = manifest(replay / 'teacher-tool-inputs.sha256')
    require(set(executed_helpers) == {'tools/validation/Verify-M3TeacherReplay.ps1',
                                    'tools/validation/replay-teacher-records.mjs',
                                    'tools/validation/replay-teacher-audit.mjs'},
            'Actual replay helper inventory missing')
    check_inputs(sorted(executed_helpers), executed_helpers)
    frontend_log = re.sub(r'\x1b\[[0-9;]*m', '', (frontend_build / 'frontend.log').read_text(encoding='utf-8'))
    require('18 passed (18)' in frontend_log and '97 passed (97)' in frontend_log and 'built in' in frontend_log,
            'Frontend full gates missing')
    api, cases = suites(api_build, 'forgeoj-api', 223)
    api_log = (api_build / 'backend.log').read_text(encoding='utf-8')
    api_log_observations = dict(
        sha256=digest(api_build / 'backend.log'),
        surefireForkExitTimeout='Surefire is going to kill self fork JVM' in api_log,
        assignmentScanWarnings=api_log.count('"event":"assignment.scan_failed"'))
    worker, worker_cases = suites(runtime_build, 'forgeoj-judge-worker', 133)
    # The gate only adds tests; replayed classes/resources/dependencies must remain exact.
    new_runtime = jar_runtime(api_build / 'forgeoj-api/forgeoj-api-0.0.1-SNAPSHOT.jar')
    old_runtime = jar_runtime(runtime_build / 'forgeoj-api/forgeoj-api-0.0.1-SNAPSHOT.jar')
    require(len(new_runtime) > 100 and new_runtime == old_runtime, 'Replayed API runtime differs')
    require(digest(runtime_build / 'forgeoj-api/forgeoj-api-0.0.1-SNAPSHOT.jar') == teacher['apiSha256'], 'API replay JAR changed')
    require(worker['jarSha256'] == teacher['workerSha256'], 'Worker replay JAR changed')
    state = load(replay / 'state.json')
    require(state['ApiHash'] == teacher['apiSha256'] and state['WorkerHash'] == teacher['workerSha256'], 'Wrong replay artifacts')
    # All M3 test declarations, not just selected gate cases, must exist and pass in the fresh XML.
    m3_cases = []
    for source in sorted((ROOT / 'forgeoj-api/src/test/java/com/forgeoj/api/classroom').glob('*Tests.java')):
        declared = re.findall(r'@Test\s+void\s+(\w+)\s*\(', source.read_text(encoding='utf-8'))
        require(declared, 'Missing M3 test declarations: ' + source.name)
        for name in declared:
            key = source.stem + '.' + name
            require(key in cases, 'Missing M3 gate case: ' + key)
            m3_cases.append(key)
    required = {
        'G1': ['AssignmentIntegrationTests.formalReplayLinksExactlyOneSnapshotAndHalfwayFailureRollsBack',
               'AssignmentIntegrationTests.acceptedBeforeDeadlineAcFinishedAfterDeadlineIsOnTimeAndHardCutoffRejectsNew',
               'AssignmentIntegrationTests.currentVersionChangeRequiresDraftReviewAndStartedFormalUsesFrozenVersion',
               'AssignmentIntegrationTests.assignmentSelfTestIsScopedAndNeverCreatesFormalGradeOrAttempt'],
        'G2': ['AssignmentIntegrationTests.allTeachingReadRoutesAndAssignmentWritesFollowCurrentRoleAcrossTransfer',
               'ClassroomIntegrationTests.createReplayJoinRaceAndRoleScope',
               'ClassroomIntegrationTests.currentSessionIsRecheckedAndPartialTransferRollsBack',
               'ClassroomProblemIntegrationTests.roleAndRoomIsolationClosePublicLibraryListSolutionAndJudgeBypasses',
               'AssignmentIntegrationTests.actualSqlDenialsKeepPublicationAndParticipantsImmutableAndWorkerUnaware'],
        'G3': ['AssignmentIntegrationTests.scheduledStartSnapshotsOnlyCurrentMembersAndAddsLateMemberExplicitly',
               'AssignmentIntegrationTests.exitKeepsOnlyOwnSummaryAndRejoinPreservesOriginalParticipant',
               'AssignmentIntegrationTests.removedParticipantKeepsMaskedOwnHistoryAndOwnerRestoreReusesParticipation',
               'AssignmentIntegrationTests.archiveStopsScheduledAndEndsActiveWithoutResurrectionAndCopyGetsFreshDraft',
               'ClassroomIntegrationTests.transferAcceptWithdrawAndExitRacesKeepSingleOwner'],
        'G4': ['AssignmentIntegrationTests.teacherGradesAndAttemptsRequireExplicitFormalScopeWithoutPrivateOrPrecompletedLeak',
               'AssignmentIntegrationTests.precompletedIsRealOwnerVersionProofAndDoesNotCreateFakeAttemptOrTeacherAccess',
               'AssignmentIntegrationTests.teacherCurrentRoleAndSessionAreRecheckedForEverySourceRead',
               'ClassroomProblemIntegrationTests.privateSelfTestUsesSharedQuotaAndCannotGrantAcOrExpandTeacherAccess']
    }
    for names in required.values():
        require(set(names).issubset(cases), 'Required gate coverage missing')
    require('JudgeTaskSnapshotLoaderIntegrationTests.currentVersionAdvanceDoesNotRedirectExistingTaskSnapshotOrHiddenTests' in worker_cases,
            'Frozen Worker basis regression missing')
    evidence_rows = []
    def saved(suffix):
        path = EVIDENCE / suffix
        value = load(path)
        evidence_rows.append(dict(path='docs/evidence/' + suffix, sha256=digest(path)))
        return value
    classroom = saved('m3-classroom/classroom-audit.json')
    private = saved('m3-private-problems/private-problems-audit.json')
    assignment = saved('m3-assignments/assignments-audit.json')
    teaching = saved('m3-teacher-records/teacher-audit.json')
    for report in (classroom, private, assignment, teaching):
        require(report['allPassed'] is True and report['multiAccountBrowser' if 'multiAccountBrowser' in report else 'browser'] is True,
                'Historical unit acceptance missing')
    require(classroom['allOwnerInvariants'] is True and classroom['httpChecks'] == 62, 'Classroom invariant evidence missing')
    require(private['realPassedValidationJobs'] == 3 and private['realFormalVerdicts'] == ['WA', 'AC', 'AC'] and
            private['membershipRevoked'] is True and private['privatePrivilegeDenials'] == 13, 'Private execution evidence missing')
    require(assignment['acceptanceBeforeDeadline'] is True and assignment['acAfterDeadline'] is True and
            assignment['formalAttempts'] == 6 and assignment['precompleted'] == 2 and assignment['realSelfTests'] == 2 and
            assignment['privilegeDenials'] == 17, 'Real assignment/deadline evidence missing')
    require(teaching['realFormal'] == 5 and teaching['participants'] == 9 and teaching['precompleted'] == 2 and
            teaching['realSelfTests'] == 1 and teaching['sqlDenials'] == 8 and teaching['sourceDigestMatches'] is True,
            'Teaching formal source evidence missing')
    for report in (private, assignment, teaching):
        require(report['commitBeforeAck'] is True and report['emptyQueues'] == 7, 'Commit/queue chain missing')
    teacher_http = saved('m3-teacher-records/teacher-http.json')
    for key in ('gradesMatchOwn', 'privateAndPreSourceDenied', 'assistantAllowed', 'revokedImmediately',
                'historicalParticipant', 'archivedReadOnly', 'lateExtensionConsistent'):
        require(teacher_http[key] is True, 'Teacher permission claim missing: ' + key)
    require(len(teacher_http['checks']) == 151, 'Teacher HTTP count changed')
    teacher_browser = saved('m3-teacher-records/teacher-browser.json')
    for key in ('ownerSource', 'memberDenied', 'assistantSource', 'demotionCleared', 'archivedLeftHistory'):
        require(teacher_browser[key] is True, 'Actual browser observation missing: ' + key)
    require(teacher_browser['submissionId'] == teacher_http['sourceSubmissionId'], 'UI source ID differs')
    for suffix in ('m3-classroom/classroom-browser.json', 'm3-assignments/assignment-browser.json',
                   'm3-assignments/runtime-browser-proof.json', 'm3-private-problems/private-problems-browser.json',
                   'm3-teacher-records/teacher-runtime.json', 'm3-teacher-records/verification.json'):
        saved(suffix)
    runtime_front = load(replay / 'teacher-runtime.json')
    require(runtime_front['readOnlySourceMount'] is True and runtime_front['files'] == frontend_count,
            'Actual read-only frontend source capture missing')
    public = saved('m3-teacher-records/audit.json')
    denials = saved('m3-teacher-records/privilege-denials.json')
    require(public['submissions'] == 11 and public['finished'] == 10 and public['cancelled'] == 1 and
            public['emptyQueues'] == 7 and denials['allDenied'] is True and denials['denials'] == 38,
            'Original public regression missing')
    for suffix in ('m3-classroom/cleanup.json', 'm3-private-problems/private-cleanup.json',
                   'm3-assignments/assignment-cleanup.json', 'm3-assignments/runtime-cleanup.json',
                   'm3-teacher-records/teacher-cleanup.json', 'm3-teacher-records/first-replay-cleanup.json'):
        saved(suffix)  # Keep historical cleanup dates; fresh daemon proof is mandatory below.
    cleanup_path = (ROOT / args.cleanup).resolve()
    require(cleanup_path.is_relative_to(ROOT / 'target'), 'Cleanup must be a local target proof')
    cleanup = load(cleanup_path)
    require(cleanup['dockerAvailable'] is True and cleanup['counts'], 'Live daemon cleanup missing')
    require(cleanup['readOnlyInspection'] is True and cleanup['collectorSha256'] ==
            digest(ROOT / 'tools/validation/Get-M3GateCleanup.ps1'), 'Cleanup collector changed')
    require(all(v == 0 for v in cleanup['counts'].values()), 'Test resources remain')
    require((datetime.now(timezone.utc) - datetime.fromisoformat(cleanup['checkedAt'])).total_seconds() < 600,
            'Live cleanup snapshot older than ten minutes')
    require(digest(ROOT / 'docs/M2-NEXT-CHAT-HANDOFF.md') == 'e08acd3d26a8cd27aca394bdac91828e13b9455a86f62133c5bafc038b18c054',
            'Preserved handoff changed')
    baseline = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
    changed = [p for p in backend_paths if runtime_manifest.get(p) != new_manifest.get(p)]
    require(changed == ['forgeoj-api/src/test/java/com/forgeoj/api/classroom/AssignmentIntegrationTests.java'],
            'Unexpected backend change beyond audit-only tests')
    result = dict(checkedAt=datetime.now(timezone.utc).isoformat(), allPassed=True, milestone='M3',
                  baseline=baseline, apiBuild=api_build.relative_to(ROOT).as_posix(), api=api,
                  apiLogObservations=api_log_observations, worker=worker,
                  workerBuild=runtime_build.relative_to(ROOT).as_posix(), frontendTests=97,
                  frontendBuild=frontend_build.relative_to(ROOT).as_posix(), sourceInputs=dict(
                      backend=backend_count, runtimeProduction=production_count, workerAndShared=worker_count,
                      frontend=frontend_count, allMatch=True, changedTestInputs=changed),
                  apiRuntimeEntries=len(new_runtime), apiRuntimeIdentical=True,
                  runtimeReplay=replay.relative_to(ROOT).as_posix(), replayApiSha256=teacher['apiSha256'],
                  freshBrowserOrWorkerReplayThisAudit=False, reusedWorkerAndFrontendTests=True,
                  allM3TestCases=m3_cases, gates=[dict(id=k, status='PASS', requiredCases=v) for k, v in required.items()],
                  historicalEvidence=evidence_rows, cleanup=cleanup, auditorSha256=digest(Path(__file__)))
    destination = EVIDENCE / 'm3-gate'
    destination.mkdir(parents=True, exist_ok=True)
    (destination / 'cleanup.json').write_bytes(cleanup_path.read_bytes())
    (destination / 'api-source-files.sha256').write_bytes((api_build / 'source-files.sha256').read_bytes())
    (destination / 'runtime-production.sha256').write_text(
        ''.join(runtime_manifest[name] + '  ' + name + '\n' for name in sorted(production_paths)),
        encoding='utf-8')
    (destination / 'frontend-runtime.sha256').write_bytes((replay / 'frontend-runtime.sha256').read_bytes())
    runtime_proof = dict(freshApiSha256=api['jarSha256'], acceptedApiSha256=teacher['apiSha256'],
                         allEqual=True, entries=[dict(path=name, freshSha256=new_runtime[name],
                                                     acceptedSha256=old_runtime[name])
                                                 for name in sorted(new_runtime)])
    (destination / 'runtime-entry-hashes.json').write_text(
        json.dumps(runtime_proof, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    (destination / 'verification.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(dict(allPassed=True, gates=4, api=api['tests'], worker=worker['tests'], frontend=97,
                          m3Cases=len(m3_cases), runtimeEntries=len(new_runtime)), ensure_ascii=False))


if __name__ == '__main__':
    main()
