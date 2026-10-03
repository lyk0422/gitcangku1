package com.example.starter.coral;

import java.time.Instant;
import java.util.List;

/**
 * 样方封存快照：固化封存UTC时刻、各状态数量（单位：株）与植株清单。
 */
public record SealView(
        String plotCode,
        String requestId,
        Instant sealedAt,
        int aliveCount,
        int deadCount,
        int pendingCount,
        List<SealPlantItem> plants) {
}