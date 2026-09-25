package com.example.starter.incident.plan;

/**
 * 方案任务执行状态：无执行行表示 PENDING 未开始；
 * IN_PROGRESS 执行中（不可被合并更换负责人或新增未满足前置依赖）；
 * COMPLETED 已完成（状态与完成事实不可回退）。
 */
public enum PlanTaskStatus {
    PENDING,
    IN_PROGRESS,
    COMPLETED
}
