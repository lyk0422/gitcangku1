package com.example.starter.incident;

import java.time.Instant;

/**
 * 遏制时刻有效屏障冻结快照实体，对应 incident_containment_snapshots 表。
 * 进入 CONTAINED 时对当时未撤销屏障写入一次，冻结其身份（barrierKey/taskKey）、
 * 比率（numerator/denominator）与任务状态（taskStatus）；后续屏障合法加强不改旧依据。
 * 时间均为 UTC。
 */
public record ContainmentSnapshot(
        long id,
        long incidentId,
        String barrierKey,
        String taskKey,
        String taskStatus,
        int numerator,
        int denominator,
        Instant frozenAt) {
}
