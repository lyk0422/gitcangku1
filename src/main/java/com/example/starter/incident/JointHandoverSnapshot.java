package com.example.starter.incident;

import java.time.Instant;

/**
 * 联合交接接受时保存的不可变闭包快照实体，对应 joint_handover_snapshots 表，
 * 每事件一行。openTasksJson 为接受时点全部 OPEN 任务
 * （任务键、版本、状态、排序后阻塞事件键）JSON；
 * escalationVersion 为未确认（OPEN）升级版本，无则为 null。
 */
public record JointHandoverSnapshot(
        long id,
        long handoverId,
        long incidentId,
        String incidentKey,
        String commander,
        String incidentStatus,
        long incidentVersion,
        String openTasksJson,
        Long escalationVersion,
        Instant createdAt) {
}
