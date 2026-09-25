package com.example.starter.blind.dto;

/**
 * 区组名额统计视图：只含数量，不含处理代码等盲底。
 * 可用名额 = 区组总席位 - 未退组且未替补的（原始或替补）参与者数；
 * 该恒等式在退组、替补前后始终成立（守恒）。
 *
 * @param experimentId       实验编号
 * @param blockNo            区组号
 * @param totalSeats         区组总席位数（固定 4）
 * @param activeParticipants 未退组且未替补的参与者数
 * @param availableSeats     可用名额（totalSeats - activeParticipants）
 */
public record BlockQuotaView(
        String experimentId,
        int blockNo,
        int totalSeats,
        long activeParticipants,
        long availableSeats
) {
}
