package com.example.starter.incident.plan;

/**
 * 方案版本状态：DRAFT 草稿（可编辑）/ PUBLISHED 当前活动版本（每事件至多一个）/
 * SUPERSEDED 已被新版本取代的历史发布版本 / MERGED 已并入新版本的草稿分支（不可变）。
 */
public enum PlanVersionStatus {
    DRAFT,
    PUBLISHED,
    SUPERSEDED,
    MERGED
}
