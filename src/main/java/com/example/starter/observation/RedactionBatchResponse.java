package com.example.starter.observation;

import java.time.Instant;
import java.util.List;

/**
 * 脱敏批次响应：一次请求生成的全部附件脱敏版本，顺序与请求条目一致。
 *
 * @param requestId   生成该批次的请求标识
 * @param batchId     脱敏批次标识
 * @param createdAtUtc 批次生成时刻（UTC）
 * @param versions    批次内各附件的脱敏版本（请求条目顺序）
 */
public record RedactionBatchResponse(
        String requestId,
        String batchId,
        Instant createdAtUtc,
        List<RedactionVersionResponse> versions) {
}
