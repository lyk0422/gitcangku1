package com.example.starter.maintenance.api.dto;

import java.time.Instant;

/**
 * 大幅读数修订见证申请视图。
 *
 * @param readingId                 被修订读数标识
 * @param requestId                 提交修订的 requestId（幂等键）
 * @param status                    PENDING 待确认 / EFFECTIVE 已见证生效 / SUPERSEDED 已被新修订取代
 * @param baseRevisionNo            提交时读数有效修订号（见证版本基准）
 * @param proposedRevisionNo        生效后占用的读数修订号
 * @param currentCumulativeMinutes  提交时有效读数累计工时快照（分钟）
 * @param proposedCumulativeMinutes 申请的新读数累计工时（分钟）
 * @param deltaMinutes              差额（分钟，保留正负号）
 * @param thresholdMinutes          申请时快照的见证阈值（分钟）
 * @param submitterId               提交修订的维护人
 * @param witnessId                 见证维护人；PENDING/SUPERSEDED 时为 null
 * @param expectedVersion           提交时设备期望版本
 * @param createdAt                 申请提交时刻（UTC）
 * @param witnessedAt               见证生效时刻（UTC）；PENDING/SUPERSEDED 时为 null
 */
public record RevisionRequestView(
        String readingId,
        String requestId,
        String status,
        int baseRevisionNo,
        int proposedRevisionNo,
        long currentCumulativeMinutes,
        long proposedCumulativeMinutes,
        long deltaMinutes,
        long thresholdMinutes,
        String submitterId,
        String witnessId,
        long expectedVersion,
        Instant createdAt,
        Instant witnessedAt) {
}
