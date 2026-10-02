package com.forgeoj.worker.sandbox;

import java.util.HashMap;
import java.util.Map;

/** Kernel counters read by a trusted control process, never from user stdout/stderr. */
record SandboxResourceEvents(long oom, long oomKills, long pidLimitHits) {
    static SandboxResourceEvents parse(String memoryEvents, String pidEvents) {
        Map<String, Long> memory = counters(memoryEvents);
        Map<String, Long> pids = counters(pidEvents);
        return new SandboxResourceEvents(required(memory, "oom"), required(memory, "oom_kill"),
                required(pids, "max"));
    }

    SandboxOutcome violationSince(SandboxResourceEvents before) {
        if (oom < before.oom || oomKills < before.oomKills || pidLimitHits < before.pidLimitHits) {
            throw invalidEvidence();
        }
        // oom_kill also counts global host OOM victims; alone it does not prove this limit failed.
        if (oomKills > before.oomKills && oom == before.oom) {
            throw new SandboxException("Sandbox memory termination has no local limit evidence");
        }
        if (pidLimitHits > before.pidLimitHits) return SandboxOutcome.SECURITY_VIOLATION;
        if (oom > before.oom) return SandboxOutcome.MEMORY_LIMIT_EXCEEDED;
        return null;
    }

    private static Map<String, Long> counters(String text) {
        if (text == null) throw invalidEvidence();
        Map<String, Long> result = new HashMap<>();
        for (String line : text.lines().toList()) {
            if (line.isBlank()) continue;
            String[] parts = line.strip().split("\\s+");
            if (parts.length != 2 || !parts[1].matches("[0-9]+")) throw invalidEvidence();
            try {
                if (result.putIfAbsent(parts[0], Long.parseLong(parts[1])) != null) throw invalidEvidence();
            } catch (NumberFormatException invalid) {
                throw invalidEvidence();
            }
        }
        return result;
    }

    private static long required(Map<String, Long> counters, String key) {
        Long value = counters.get(key);
        if (value == null) throw invalidEvidence();
        return value;
    }

    private static SandboxException invalidEvidence() {
        return new SandboxException("Sandbox resource evidence is invalid");
    }
}
