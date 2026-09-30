package com.example.starter.maintenance.domain;

import java.time.Instant;

/**
 * 待见证的大幅读数修订（reading_witness 行）。超出设备见证阈值的修订先进入待确认，
 * 经另一名维护人见证同一读数版本后生效，生效后本行删除，历史留痕见 reading_revision。
 *
 * @param equipmentId               所属设备标识
 * @param readingId                 读数标识
 * @param pendingRevisionNo         待生效修订号（= 提交时读数当前修订号 + 1）
 * @param cumulativeMinutes         待确认的累计工时（分钟）
 * @param thresholdMinutes          提交时设备见证阈值快照（分钟）
 * @param baseCumulativeMinutes     提交时读数有效累计工时快照（分钟）
 * @param differenceMinutes         差额 = 待确认值 - 有效基准值（分钟）
 * @param requestId                 提交修订请求的 requestId
 * @param submittedBy               提交修订的维护人标识
 * @param submittedAt               修订提交时刻（UTC）
 */
public record WitnessRevision(
        String equipmentId,
        String readingId,
        int pendingRevisionNo,
        long cumulativeMinutes,
        long thresholdMinutes,
        long baseCumulativeMinutes,
        long differenceMinutes,
        String requestId,
        String submittedBy,
        Instant submittedAt) {
}
