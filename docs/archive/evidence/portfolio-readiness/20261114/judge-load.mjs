// Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
// Fixed HTTPS destination inside a fresh owned validation project. Never prints cookies.
import assert from "node:assert/strict";
import https from "node:https";
import { readFile, appendFile, writeFile } from "node:fs/promises";
import { createHash, randomUUID } from "node:crypto";
import { performance } from "node:perf_hooks";
const ca = await readFile("/public/ca.pem");
const agent = new https.Agent({
  keepAlive: true,
  maxSockets: 3,
  maxFreeSockets: 3,
});
const cookies = new Map();
const hash = (s) => createHash("sha256").update(s).digest("hex");
const epoch = performance.now();
const rows = [];
let journal = Promise.resolve();
const save = (row) => {
  // Windows shared mounts do not provide reliable concurrent O_APPEND semantics.
  journal = journal.then(() =>
    appendFile("/output/jobs.ndjson", JSON.stringify(row) + "\n"),
  );
  return journal;
};
async function request(path, body, csrf, key) {
  return new Promise((resolve, reject) => {
    const req = https.request(
      {
        hostname: "web",
        servername: "oj.test",
        port: 443,
        path,
        ca,
        agent,
        method: body === undefined ? "GET" : "POST",
        headers: {
          Host: "oj.test:18443",
          Origin: "https://oj.test:18443",
          Cookie: [...cookies].map(([k, v]) => k + "=" + v).join("; "),
          ...(body === undefined ? {} : { "Content-Type": "application/json" }),
          ...(csrf ? { [csrf.headerName]: csrf.token } : {}),
          ...(key ? { "Idempotency-Key": key } : {}),
        },
      },
      (res) => {
        for (const c of res.headers["set-cookie"] ?? []) {
          const pair = c.split(";")[0],
            at = pair.indexOf("=");
          cookies.set(pair.slice(0, at), pair.slice(at + 1));
        }
        let text = "";
        res.setEncoding("utf8");
        res.on("data", (part) => {
          text += part;
          if (text.length > 131072) req.destroy(new Error("Response bound"));
        });
        res.on("end", () => {
          try {
            assert.ok(
              res.statusCode === 200 || res.statusCode === 202,
              "Unexpected HTTP " + res.statusCode,
            );
            resolve(JSON.parse(text));
          } catch (e) {
            reject(e);
          }
        });
      },
    );
    req.setTimeout(10000, () => req.destroy(new Error("Request deadline")));
    req.on("error", reject);
    req.end(body === undefined ? undefined : JSON.stringify(body));
  });
}
const code =
  "import java.util.Scanner; public class Main { public static void main(String[] args) { Scanner s=new Scanner(System.in); System.out.println(s.nextLong()+s.nextLong()); } }";
let csrf;
async function job(stage, index) {
  const requestId = randomUUID(),
    sourceCode = "// judge throughput " + stage + " " + index + "\n" + code;
  const started = performance.now();
  await save({
    event: "intent",
    stage,
    index,
    requestId,
    sourceSha256: hash(sourceCode),
    offsetMs: started - epoch,
  });
  const accepted = await request(
    "/api/v1/problems/sum-two-integers/submissions",
    { language: "JAVA_21", sourceCode },
    csrf,
    requestId,
  );
  const acceptanceMs = performance.now() - started;
  assert.match(accepted.submissionId, /^[a-f0-9-]{36}$/);
  await save({
    event: "accepted",
    stage,
    index,
    requestId,
    submissionId: accepted.submissionId,
    acceptanceMs,
  });
  const deadline = performance.now() + 90000;
  let latest;
  do {
    latest = await request("/api/v1/submissions/" + accepted.submissionId);
    if (latest.processingStatus === "FINISHED") break;
    assert.ok(!["SYSTEM_ERROR", "CANCELLED"].includes(latest.processingStatus));
    await new Promise((r) => setTimeout(r, 200));
  } while (performance.now() < deadline);
  assert.equal(latest.processingStatus, "FINISHED");
  assert.equal(latest.verdict, "AC");
  const row = {
    event: "finished",
    stage,
    index,
    requestId,
    submissionId: accepted.submissionId,
    sourceSha256: hash(sourceCode),
    acceptanceMs,
    clientVisibleMs: performance.now() - started,
    verdict: latest.verdict,
  };
  rows.push(row);
  await save(row);
}
async function stage(name, count, concurrency) {
  const start = performance.now(),
    startedAt = new Date().toISOString();
  let next = 0;
  await Promise.all(
    Array.from({ length: concurrency }, async () => {
      while (next < count) {
        const index = next++;
        await job(name, index);
      }
    }),
  );
  const result = {
    name,
    count,
    concurrency,
    startedAt,
    finishedAt: new Date().toISOString(),
    elapsedMs: performance.now() - start,
  };
  await appendFile("/output/stages.ndjson", JSON.stringify(result) + "\n");
  console.log(JSON.stringify({ stage: name, finished: count }));
}
let failure;
try {
  const session = await request("/api/v1/auth/session");
  const login = await request(
    "/api/v1/auth/login",
    { username: "learner", password: "forgeoj-dev-only" },
    session.csrf,
  );
  assert.equal(login.authenticated, true);
  csrf = login.csrf;
  await stage("warmup", 5, 1);
  await stage("sequential", 20, 1);
  for (let round = 1; round <= 3; round++)
    await stage("backlog-" + round, 10, 3);
  await writeFile(
    "/output/client-result.json",
    JSON.stringify(
      {
        status: "PASS",
        finished: rows.length,
        pollIntervalMs: 200,
        clientClock: "performance.now",
        tlsValidated: true,
      },
      null,
      2,
    ) + "\n",
  );
} catch (e) {
  failure = e;
  await writeFile(
    "/output/client-result.json",
    JSON.stringify(
      {
        status: "FAIL",
        finished: rows.length,
        message: "Inspect original intents; no automatic retries or resume",
      },
      null,
      2,
    ) + "\n",
  );
} finally {
  agent.destroy();
}
if (failure) throw failure;
