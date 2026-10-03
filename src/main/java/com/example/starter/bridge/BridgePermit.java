package com.example.starter.bridge;

import java.time.Instant;

/**
 * 便桥通行许可记录，对应 bridge_permit 表。
 * 所有时刻均为 UTC；actualEntryAt / actualExitAt 及操作幂等键未发生时为空。
 */
public record BridgePermit(
        /** 许可主键。 */
        long id,
        /** 签发幂等请求键，全局唯一。 */
        String requestId,
        /** 车牌代号。 */
        String plateNo,
        /** 车辆总质量，单位千克。 */
        int totalMassKg,
        /** 预计入桥时刻，UTC。 */
        Instant plannedEntryAt,
        /** 许可窗口起点（含），UTC。 */
        Instant windowStartAt,
        /** 许可窗口终点（不含），UTC。 */
        Instant windowEndAt,
        /** 许可状态，见 {@link PermitStatus}。 */
        PermitStatus status,
        /** 实际进桥时刻，UTC；未进桥为空。 */
        Instant actualEntryAt,
        /** 实际出桥时刻，UTC；未出桥为空。 */
        Instant actualExitAt,
        /** 进桥确认幂等请求键；未进桥为空。 */
        String entryRequestId,
        /** 出桥释放幂等请求键；未出桥为空。 */
        String exitRequestId,
        /** 撤销幂等请求键；未撤销为空。 */
        String cancelRequestId,
        /** 记录创建时刻，UTC。 */
        Instant createdAt,
        /** 记录最后变更时刻，UTC。 */
        Instant updatedAt) {
}