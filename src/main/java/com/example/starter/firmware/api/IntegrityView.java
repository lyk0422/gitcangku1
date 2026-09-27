package com.example.starter.firmware.api;

import java.util.List;

/**
 * 任务当前代次完整性诊断视图。数量字段返回实际值、要求值与差额；未判定的字段为 null。
 */
public record IntegrityView(long taskId, int attemptNo, String status,
                            int receivedCount, int requiredCount, int missingCount,
                            List<Integer> missingIndices,
                            String verdict, String verdictReason,
                            String expectedAggregate, String actualAggregate, String decidedAtUtc) {
}
