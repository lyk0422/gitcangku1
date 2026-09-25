package com.example.starter.incident;

/**
 * 处置任务对前置事件的依赖边实体，对应 incident_task_blockers 表。
 * blockerIncidentId 必须与任务所属事件处于同一域，跨域依赖写入即返回 422。
 */
public record TaskBlocker(
        long id,
        long taskId,
        long blockerIncidentId) {
}
