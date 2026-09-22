package com.example.starter.incident;

/**
 * 跨事件依赖图的一条有向边：sourceIncidentId 的任务依赖 blockedIncidentId（目标事件）。
 */
public record TaskDependencyEdge(long sourceIncidentId, long blockedIncidentId) {
}
