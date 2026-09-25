package com.example.starter.plan.model;

/**
 * 日计划状态：DRAFT 草稿可整体替换占用；PUBLISHED 已发布占用生效；
 * CANCELLED 已取消，时隙立即释放但历史占用保留；PREEMPTED 被高等级计划抢占（终态），
 * 时隙释放、历史占用与发布历史保留，但不可再改签、取消或作为后续抢占目标。
 */
public enum PlanStatus {
    DRAFT,
    PUBLISHED,
    CANCELLED,
    PREEMPTED
}
