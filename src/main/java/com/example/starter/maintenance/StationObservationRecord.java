package com.example.starter.maintenance;

import java.time.Instant;

/**
 * 站点观测原始记录：对应 station_observation 表的一行，提交后不可变。
 *
 * @param submissionId    观测提交唯一标识
 * @param clusterId       冲突簇标识
 * @param stationId       观测站标识
 * @param observedAtUtc   观测时刻（UTC，客户端提供）
 * @param submittedAtUtc  提交受理时刻（UTC，服务端时钟，维护窗口匹配基准）
 * @param location        观测地点
 * @param reading         观测读数（十进制字符串，最多三位小数）
 * @param note            观测备注
 * @param maintenanceFlag 维护标记：NONE / MAINTENANCE_AFFECTED
 * @param windowId        提交时刻命中的维护窗口标识；未命中为 null
 * @param windowVersion   命中窗口的维护版本；未命中为 null
 * @param requestId       批次提交请求标识
 * @param batchSeq        批内序号（从 0 开始）
 */
public record StationObservationRecord(
        String submissionId,
        String clusterId,
        String stationId,
        Instant observedAtUtc,
        Instant submittedAtUtc,
        String location,
        String reading,
        String note,
        String maintenanceFlag,
        String windowId,
        Integer windowVersion,
        String requestId,
        int batchSeq) {

    /** 维护标记：未受影响。 */
    public static final String FLAG_NONE = "NONE";
    /** 维护标记：提交时刻命中维护窗口，不可作为自动合并胜出版本。 */
    public static final String FLAG_MAINTENANCE_AFFECTED = "MAINTENANCE_AFFECTED";
}
