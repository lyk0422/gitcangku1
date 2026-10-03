package com.example.starter.api.dto;

import java.time.Instant;
import java.util.List;

/** 整批放行响应：含冻结明细；replayed=true 表示同参幂等重放。 */
public record ReleaseResponse(
        long releaseId,
        long batchId,
        int targetVersion,
        Instant releasedAt,
        List<BatchView.ReleaseLineView> lines,
        boolean replayed) {
}