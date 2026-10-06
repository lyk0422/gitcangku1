package com.example.starter.incident;

import java.time.Instant;

/**
 * 遏制冻结屏障快照实体，对应 incident_containment_barriers 表。
 * 遏制成功时按当时有效屏障（任务 DONE 且未撤销）冻结其身份、削减比率与任务状态，
 * 作为遏制决策的历史依据；之后的合法加强不回改该快照。
 */
public record ContainmentBarrier(
        long id,
        long modelId,
        String barrierKey,
        String taskKey,
        String taskStatus,
        int reduceP,
        int reduceQ,
        Instant createdAt) {
}
