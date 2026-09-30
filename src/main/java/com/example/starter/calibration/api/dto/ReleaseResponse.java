package com.example.starter.calibration.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 批量放行成功响应。重放同一 requestId 时原样返回首次快照内容。
 *
 * @param requestId  放行请求 ID（幂等键）
 * @param batchId    放行批次 ID
 * @param releasedBy 放行人（X-Actor-Id）
 * @param temperature 放行环境温度（℃）
 * @param humidity    放行环境湿度（%RH）
 * @param coefficientVersion 本次命中的系数版本标签
 * @param releasedAt 放行时间（UTC）
 * @param released   已放行的测量键
 * @param replayed   是否为 requestId 重放（true 表示返回的是首次快照，未重新计算）
 */
public record ReleaseResponse(
        String requestId,
        String batchId,
        String releasedBy,
        String temperature,
        String humidity,
        String coefficientVersion,
        Instant releasedAt,
        List<String> released,
        boolean replayed) {
}
