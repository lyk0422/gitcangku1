package com.example.starter.plan.model;

/**
 * 日计划状态：DRAFT 草稿可整体替换占用；PUBLISHED 已发布占用生效；
 * CANCELLED 已取消，时隙立即释放但历史占用保留；
 * SUSPENDED 因区段封锁切换被挂起，时隙立即释放但历史占用保留，可经替代链追溯。
 * 已发布、已取消与已挂起均不可再修改。
 */
public enum PlanStatus {
    DRAFT,
    PUBLISHED,
    CANCELLED,
    SUSPENDED
}
