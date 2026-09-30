package com.example.starter.maintenance.api.dto;

import java.time.Instant;

/**
 * 待确认大幅修订视图。见证生效后申请删除（历史留痕见读数修订历史）。
 *
 * @param equipmentId               所属设备标识
 * @param readingId                 读数标识
 * @param pendingRevisionNo         待生效修订号（见证时必须与读数当前修订号匹配）
 * @param baseCumulativeMinutes     提交时有效基准累计工时（分钟）
 * @param pendingCumulativeMinutes  待确认累计工时（分钟）
 * @param differenceMinutes         差额 = 待确认值 - 有效基准值（分钟）
 * @param thresholdMinutes          提交申请时设备见证阈值快照（分钟）
 * @param requestId                 提交修订的 requestId
 * @param submittedBy               提交修订的维护人标识
 * @param submittedAt               修订提交时刻（UTC）
 * @param equipmentVersion          操作后的设备版本号（仅写操作响应中有意义，查询时为当前版本）
 */
public record WitnessResponse(
        String equipmentId,
        String readingId,
        int pendingRevisionNo,
        long baseCumulativeMinutes,
        long pendingCumulativeMinutes,
        long differenceMinutes,
        long thresholdMinutes,
        String requestId,
        String submittedBy,
        Instant submittedAt,
        long equipmentVersion) {
}
