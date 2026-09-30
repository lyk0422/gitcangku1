package com.example.starter.maintenance.api.dto;

import java.time.Instant;

/**
 * 读数视图（当前有效值 + 可选待确认修订）。
 *
 * @param equipmentId                所属设备标识
 * @param readingId                  读数标识
 * @param sampledAt                  UTC 采样时刻
 * @param cumulativeMinutes          当前有效累计工时（分钟）；待确认修订未见证前不改变该值
 * @param revisionNo                 当前有效修订号，初始 1
 * @param anchored                   是否已被某条保养记录锚定（锚定后不可修订）
 * @param pendingCumulativeMinutes   待确认累计工时（分钟）；无待确认修订时为 null
 * @param pendingRevisionNo          待生效修订号；无待确认修订时为 null
 * @param differenceMinutes          待确认值与有效基准值的差额（分钟）；无待确认修订时为 null
 * @param thresholdMinutes           提交申请时设备见证阈值快照（分钟）；无待确认修订时为 null
 * @param submittedBy                待确认修订提交人；无待确认修订时为 null
 * @param witnessedBy                待确认修订见证人；无待确认修订或未见证时为 null
 * @param equipmentVersion           操作后的设备版本号（仅写操作响应中有意义，查询时为当前版本）
 */
public record ReadingResponse(
        String equipmentId,
        String readingId,
        Instant sampledAt,
        long cumulativeMinutes,
        int revisionNo,
        boolean anchored,
        Long pendingCumulativeMinutes,
        Integer pendingRevisionNo,
        Long differenceMinutes,
        Long thresholdMinutes,
        String submittedBy,
        String witnessedBy,
        long equipmentVersion) {
}
