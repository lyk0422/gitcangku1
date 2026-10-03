package com.example.starter.bridge;

import java.time.Instant;

/**
 * 便桥通行许可（对应 bridge_permit 表）。时间字段均为 UTC；未发生的时刻为 null。
 *
 * @param id 许可主键
 * @param requestId 签发幂等键
 * @param plateNo 车牌代号
 * @param massKg 车辆总质量，单位千克
 * @param plannedEntryAt 预计入桥时刻
 * @param windowStart 锁定窗口起点（左闭）
 * @param windowEnd 锁定窗口终点（右开）
 * @param status 许可状态
 * @param enteredAt 实际进桥时刻，未进桥为 null
 * @param exitedAt 实际出桥时刻，未出桥为 null
 * @param enterRequestId 进桥确认幂等键
 * @param exitRequestId 出桥释放幂等键
 * @param cancelRequestId 撤销幂等键
 * @param createdAt 记录创建时间
 * @param updatedAt 记录更新时间
 */
public record BridgePermit(
        long id,
        String requestId,
        String plateNo,
        long massKg,
        Instant plannedEntryAt,
        Instant windowStart,
        Instant windowEnd,
        PermitStatus status,
        Instant enteredAt,
        Instant exitedAt,
        String enterRequestId,
        String exitRequestId,
        String cancelRequestId,
        Instant createdAt,
        Instant updatedAt) {

    /**
     * 判断许可参数是否与一次签发请求完全一致（用于幂等重放判定）。
     */
    public boolean matchesApplication(String plateNo, long massKg, Instant plannedEntryAt) {
        return this.plateNo.equals(plateNo)
                && this.massKg == massKg
                && this.plannedEntryAt.equals(plannedEntryAt);
    }
}