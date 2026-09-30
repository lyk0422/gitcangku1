package com.example.starter.maintenance.api.dto;

import java.time.Instant;

/**
 * 读数视图（当前值）。存在待确认大幅修订时，pending* 字段展示待确认信息；
 * 有效值字段（cumulativeMinutes/revisionNo）始终只反映已生效读数，待确认值不覆盖它们。
 *
 * @param equipmentId               所属设备标识
 * @param readingId                 读数标识
 * @param sampledAt                 UTC 采样时刻
 * @param cumulativeMinutes         当前已生效累计工时（分钟），待确认修订不覆盖该值
 * @param revisionNo                当前已生效修订号，初始 1
 * @param anchored                  是否已被某条保养记录锚定（锚定后不可修订）
 * @param equipmentVersion          操作后的设备版本号（仅写操作响应中有意义，查询时为当前版本）
 * @param pendingRequestId          待确认修订申请的 requestId；无待确认申请时为 null
 * @param pendingCumulativeMinutes  待确认累计工时（分钟）；无待确认申请时为 null
 * @param pendingDeltaMinutes       待确认值与提交时有效值的差额（分钟，保留正负号）；无待确认申请时为 null
 * @param pendingThresholdMinutes   申请时快照的见证阈值（分钟）；无待确认申请时为 null
 * @param pendingSubmitterId        待确认修订提交人；无待确认申请时为 null
 */
public record ReadingResponse(
        String equipmentId,
        String readingId,
        Instant sampledAt,
        long cumulativeMinutes,
        int revisionNo,
        boolean anchored,
        long equipmentVersion,
        String pendingRequestId,
        Long pendingCumulativeMinutes,
        Long pendingDeltaMinutes,
        Long pendingThresholdMinutes,
        String pendingSubmitterId) {
}
