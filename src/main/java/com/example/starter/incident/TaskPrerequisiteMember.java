package com.example.starter.incident;

import java.time.Instant;

/**
 * 择一前置组成员实体，对应 task_prerequisite_members 表。
 * 每组 2~5 个成员，以事件号与任务号标识，可跨事件；
 * 仅 DONE 满足前置，CANCELLED 不满足，全部取消仍阻塞。
 * expectedTaskVersion 为配置时声明的成员任务期望版本，
 * 与成员当前版本不一致时配置失败（409）。时间均为 UTC。
 */
public record TaskPrerequisiteMember(
        long id,
        long groupId,
        long memberIncidentId,
        long memberTaskId,
        int expectedTaskVersion,
        Instant createdAt) {
}
