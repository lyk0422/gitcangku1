package com.example.starter.incident;

import java.time.Instant;

/**
 * 任务双人监护配置实体，对应 task_guardians 表。每个任务至多一条；
 * guardianOne/guardianTwo 为固定的两个不同合成操作者，启用后不可更换；
 * version 为监护配置版本，启用时从 1 开始，完成时必须显式引用该期望版本。
 * 时间均为 UTC。
 */
public record TaskGuardian(
        long id,
        long taskId,
        String guardianOne,
        String guardianTwo,
        int version,
        String createdBy,
        Instant createdAt) {

    /**
     * 判断操作者是否为被固定的监护人之一。
     */
    public boolean isGuardian(String actor) {
        return guardianOne.equals(actor) || guardianTwo.equals(actor);
    }
}
