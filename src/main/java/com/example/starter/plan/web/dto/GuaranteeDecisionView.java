package com.example.starter.plan.web.dto;

import java.time.Instant;

/**
 * 单趟后车的保障改签决策视图：固化决策所依据的前车版本、原新始发时刻、
 * 后移分钟数与修复的保障差额。
 */
public record GuaranteeDecisionView(String batchKey, String scheduleKey, int newVersion,
                                    int predecessorVersion,
                                    Instant oldFirstStartUtc, Instant newFirstStartUtc,
                                    long shiftMinutes, long deficitMinutes) {
}
