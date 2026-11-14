/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.api.classroom;

import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
    name = "forgeoj.assignments.scheduler.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class AssignmentScheduler {
  private final AssignmentMapper mapper;
  private final AssignmentLifecycle lifecycle;

  public AssignmentScheduler(AssignmentMapper mapper, AssignmentLifecycle lifecycle) {
    this.mapper = mapper;
    this.lifecycle = lifecycle;
  }

  @Scheduled(fixedDelayString = "${forgeoj.assignments.scheduler.fixed-delay-ms:2000}")
  public void tick() {
    try {
      for (String room : mapper.dueRooms()) lifecycle.refresh(room);
    } catch (RuntimeException e) {
      LoggerFactory.getLogger(AssignmentScheduler.class)
          .atWarn()
          .addKeyValue("event", "assignment.scan_failed")
          .log("Assignment scan unavailable");
    }
  }
}
