package com.example.starter.bridge.api;

import java.time.Instant;

import com.example.starter.bridge.BridgePermit;
import com.example.starter.bridge.PermitStatus;

/**
 * 许可视图。
 *
 * @param id 许可主键
 * @param requestId 签发幂等键
 * @param plateNo 车牌代号
 * @param massKg 车辆总质量，单位千克
 * @param plannedEntryAt 预计入桥时刻，UTC
 * @param windowStart 锁定窗口起点（左闭），UTC
 * @param windowEnd 锁定窗口终点（右开），UTC
 * @param status 许可状态
 * @param enteredAt 实际进桥时刻，UTC；未进桥为 null
 * @param exitedAt 实际出桥时刻，UTC；未出桥为 null
 */
public record PermitView(
        long id,
        String requestId,
        String plateNo,
        long massKg,
        Instant plannedEntryAt,
        Instant windowStart,
        Instant windowEnd,
        PermitStatus status,
        Instant enteredAt,
        Instant exitedAt) {

    /**
     * 从领域对象构建视图。
     */
    public static PermitView from(BridgePermit permit) {
        return new PermitView(permit.id(), permit.requestId(), permit.plateNo(), permit.massKg(),
                permit.plannedEntryAt(), permit.windowStart(), permit.windowEnd(), permit.status(),
                permit.enteredAt(), permit.exitedAt());
    }
}