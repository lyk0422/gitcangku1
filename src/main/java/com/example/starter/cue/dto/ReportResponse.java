package com.example.starter.cue.dto;

import java.time.LocalDateTime;

/**
 * 单次执行回报的受理结果（也是幂等重放时返回的原回报）。
 *
 * @param id           回报记录 ID
 * @param seqNo        提示点序号
 * @param success      是否成功
 * @param failReason   失败原因；成功或无失败时为空
 * @param requestId    幂等键
 * @param operatorCode 操作员标识，可空
 * @param replayed     是否为同一 requestId 的重放返回（首次受理为 FALSE）
 * @param reportedAt   回报受理时间（Asia/Shanghai）
 */
public record ReportResponse(
        Long id,
        Integer seqNo,
        boolean success,
        String failReason,
        String requestId,
        String operatorCode,
        boolean replayed,
        LocalDateTime reportedAt) {
}
