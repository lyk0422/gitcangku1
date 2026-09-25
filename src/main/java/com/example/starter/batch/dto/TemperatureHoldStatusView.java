package com.example.starter.batch.dto;

import java.time.Instant;

/**
 * 温控冻结状态查询视图。held 为 true 表示批次当前处于 TEMPERATURE_HOLD；
 * 从未冻结时各冻结字段为 null；已解除时 releasedAt/releaseActor/investigationNote 记录最近一次解除。
 */
public record TemperatureHoldStatusView(
        String batchKey,
        boolean held,
        String batchStatus,
        String preStatus,
        Instant heldAt,
        Instant releasedAt,
        String releaseActor,
        String investigationNote
) {
}
