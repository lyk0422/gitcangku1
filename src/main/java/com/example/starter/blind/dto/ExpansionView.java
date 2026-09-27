package com.example.starter.blind.dto;

/**
 * 区组扩容结果视图：追加的区组仅包含未退役代码的席位。
 *
 * @param experimentId    实验编号
 * @param addedBlocks     本次追加的区组数
 * @param blockCount      扩容后的区组总数
 * @param addedSeats      本次实际新增的席位数（已退役代码不产生新席位）
 * @param status          实验状态 OPEN / CLOSED
 */
public record ExpansionView(
        String experimentId,
        int addedBlocks,
        int blockCount,
        int addedSeats,
        String status
) {
}
