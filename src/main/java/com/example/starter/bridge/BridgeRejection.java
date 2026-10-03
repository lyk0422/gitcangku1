package com.example.starter.bridge;

import java.time.Instant;

/**
 * 许可申请拒绝记录，对应 bridge_rejection 表；仅用于查询拒绝依据，不占用签发幂等键。
 */
public record BridgeRejection(
        /** 拒绝记录主键。 */
        long id,
        /** 被拒绝申请的请求键，可重复出现。 */
        String requestId,
        /** 车牌代号。 */
        String plateNo,
        /** 申请车辆总质量，单位千克。 */
        int totalMassKg,
        /** 申请预计入桥时刻，UTC。 */
        Instant plannedEntryAt,
        /** 拒绝原因代码，如 OVERWEIGHT / TIME_REGRESSION / WINDOW_OVERLAP。 */
        String reasonCode,
        /** 拒绝原因说明。 */
        String reasonDetail,
        /** 拒绝记录创建时刻，UTC。 */
        Instant createdAt) {
}