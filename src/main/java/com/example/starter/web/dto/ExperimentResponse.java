package com.example.starter.web.dto;

/**
 * 创建实验响应。
 *
 * @param experimentId 实验编号
 * @param blockCount   固定区组数量
 * @param seatCount    固定席位总数（区组数 × 4）
 * @param status       实验状态
 */
public record ExperimentResponse(
        String experimentId,
        int blockCount,
        int seatCount,
        String status
) {
}
