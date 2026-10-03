package com.example.starter.bridge.dto;

import java.time.Instant;

import com.example.starter.bridge.BridgePermit;

/**
 * 许可视图；时刻均为 UTC，未发生的时刻为空。
 */
public record PermitResponse(
        /** 许可主键。 */
        long id,
        /** 签发幂等请求键。 */
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
        /** 许可状态：APPROVED / ENTERED / EXITED / CANCELLED。 */
        String status,
        /** 实际进桥时刻，UTC；未进桥为空。 */
        Instant actualEntryAt,
        /** 实际出桥时刻，UTC；未出桥为空。 */
        Instant actualExitAt) {

    /** 从持久化记录构造视图。 */
    public static PermitResponse from(BridgePermit permit) {
        return new PermitResponse(
                permit.id(),
                permit.requestId(),
                permit.plateNo(),
                permit.totalMassKg(),
                permit.plannedEntryAt(),
                permit.windowStartAt(),
                permit.windowEndAt(),
                permit.status().name(),
                permit.actualEntryAt(),
                permit.actualExitAt());
    }
}