package com.example.starter.model;

import java.time.Instant;

/**
 * 除冰作业完成记录，一次时隙最多一条，历史不可覆盖。
 *
 * @param id 主键
 * @param slotId 关联时隙 ID，唯一
 * @param fluidType 使用液型
 * @param completedAt 完成时刻（UTC）
 * @param deadlineAt 起飞截止时刻（UTC，不含），完成时刻加液型有效期
 */
public record DeicingCompletion(
        Long id,
        long slotId,
        FluidType fluidType,
        Instant completedAt,
        Instant deadlineAt) {
}