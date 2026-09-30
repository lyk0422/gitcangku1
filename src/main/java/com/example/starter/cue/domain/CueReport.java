package com.example.starter.cue.domain;

import java.time.LocalDateTime;

/**
 * 执行回报 PO；成功与有因失败落库，被 409 拒绝的请求不入库、不占用幂等键。
 *
 * @param id           主键
 * @param sessionId    所属场次 ID
 * @param seqNo        回报的提示点序号
 * @param success      TRUE 成功并推进序号；FALSE 失败、记录原因、不推进
 * @param failReason   失败原因；仅失败时非空
 * @param requestId    幂等键，同场次唯一
 * @param operatorCode 操作员标识，可空
 * @param reportedAt   受理时间（Asia/Shanghai）
 */
public record CueReport(
        Long id,
        Long sessionId,
        Integer seqNo,
        boolean success,
        String failReason,
        String requestId,
        String operatorCode,
        LocalDateTime reportedAt) {
}
