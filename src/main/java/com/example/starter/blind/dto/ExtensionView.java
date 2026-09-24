package com.example.starter.blind.dto;

/**
 * 扩容结果视图；不含任何新席位的处理代码或席位序号。
 *
 * @param experimentId     实验编号
 * @param extensionKey     扩容幂等键
 * @param expectedVersion  提交时的期望（扩容前）版本号
 * @param version          扩容后的实验版本号（扩容前版本 + 1）
 * @param blockCount       扩容后的区组总数（不超过 16）
 * @param seatsPerBlock    每区组席位数，固定为 4
 * @param totalSeats       扩容后的总席位数
 * @param addedBlockCount  本次追加的区组数量
 * @param status           实验状态 OPEN / CLOSED
 */
public record ExtensionView(
        String experimentId,
        String extensionKey,
        int expectedVersion,
        int version,
        int blockCount,
        int seatsPerBlock,
        int totalSeats,
        int addedBlockCount,
        String status
) {
}
