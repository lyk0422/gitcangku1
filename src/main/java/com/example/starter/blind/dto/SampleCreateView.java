package com.example.starter.blind.dto;

/**
 * 创建样本响应（协调员视图）：只含一级码，绝不含二级码、分析员或结果值。
 *
 * @param experimentId  实验编号
 * @param participantId 合成参与者编号
 * @param primaryCode   一级随机码，全局唯一，协调员转交分析员
 * @param status        样本状态：CREATED
 * @param createdAt     创建时间，Unix 毫秒，UTC
 */
public record SampleCreateView(
        String experimentId,
        String participantId,
        String primaryCode,
        String status,
        long createdAt
) {
}
