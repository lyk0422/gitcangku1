package com.example.starter.incident;

/**
 * 任务跨事件阻塞边实体，对应 incident_task_blocks 表。
 * 表示“当前事件任务依赖目标事件”：blockedIncidentId 为被依赖的目标事件 id。
 */
public record TaskBlock(
        long id,
        long taskId,
        long blockedIncidentId,
        String blockedIncidentKey) {
}
