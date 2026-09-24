package com.example.starter.blind.dto;

/**
 * 扩容结果视图；只暴露区组层面的容量与版本信息。
 * 严禁包含新区组席位的处理代码 treatment 或席位序号 seatNo。
 *
 * @param experimentId    实验编号
 * @param extensionKey    扩容幂等键
 * @param expectedVersion 请求携带的期望版本（扩容前版本）
 * @param fromVersion     扩容前版本
 * @param version         扩容后实验版本（fromVersion + 1）
 * @param addedBlockCount 本次追加的区组数量（1~4）
 * @param firstBlockNo    本次首个新区组号（现有最大区组号 + 1）
 * @param lastBlockNo     本次末尾新区组号
 * @param blockCount      扩容后区组总数（不超过 16）
 * @param seatsPerBlock   每区组席位数，固定 4
 * @param totalSeats      扩容后总席位数
 * @param status          扩容提交时实验状态，扩容仅允许 OPEN，故恒为 OPEN
 * @param createdAt       扩容提交时间，Unix 毫秒，UTC
 */
public record BlockExtensionView(
        String experimentId,
        String extensionKey,
        int expectedVersion,
        int fromVersion,
        int version,
        int addedBlockCount,
        int firstBlockNo,
        int lastBlockNo,
        int blockCount,
        int seatsPerBlock,
        int totalSeats,
        String status,
        long createdAt
) {
}
