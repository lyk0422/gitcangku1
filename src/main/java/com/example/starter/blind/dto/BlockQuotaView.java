package com.example.starter.blind.dto;

/**
 * 区组名额统计视图（不含处理代码）。
 * 可用名额 = 区组总席位 − 已占用分配序号数；替补不新建分配序号，故替补前后可用名额守恒。
 *
 * @param experimentId          实验编号
 * @param blockNo               区组号
 * @param capacity              区组总席位数
 * @param allocatedSlots        已占用分配序号数（含在组与已退组未替补，退组不释放席位）
 * @param availableSlots        可用名额 = capacity − allocatedSlots，替补前后守恒
 * @param activeParticipants    未退组且未替补的原始或替补参与者数
 * @param withdrawnParticipants 已退组且未替补的参与者数（席位仍占用）
 * @param replacedParticipants  本区组已替补记录数（原参与者转入 REPLACED 终态）
 */
public record BlockQuotaView(
        String experimentId,
        int blockNo,
        int capacity,
        long allocatedSlots,
        long availableSlots,
        long activeParticipants,
        long withdrawnParticipants,
        long replacedParticipants
) {
}
