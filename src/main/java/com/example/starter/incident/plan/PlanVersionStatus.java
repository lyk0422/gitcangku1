package com.example.starter.incident.plan;

/**
 * 方案版本状态：PUBLISHED 已发布（任务集/边集不可变，仅活动版本任务执行状态可前进）、
 * DRAFT 草稿修订（可整体替换任务与边）、MERGED 已被合并的草稿（不可变）。
 */
public enum PlanVersionStatus {
    DRAFT,
    PUBLISHED,
    MERGED
}
