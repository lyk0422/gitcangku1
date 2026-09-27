package com.example.starter.sampling;

/**
 * 批量合并幂等去重记录：对应 sampling_merge_batch 表的一行，仅成功批次会落库。
 *
 * @param batchId        批量合并标识（幂等键）
 * @param fingerprint    批次内容指纹，同键异参判定 409
 * @param responseStatus 成功响应的 HTTP 状态码；处理过程中为 null
 * @param responseBody   成功响应体（JSON 原文），用于同键同参重放；处理过程中为 null
 */
public record MergeBatchEntry(
        String batchId,
        String fingerprint,
        Integer responseStatus,
        String responseBody) {
}
