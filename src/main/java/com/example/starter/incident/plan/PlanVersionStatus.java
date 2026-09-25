package com.example.starter.incident.plan;

/**
 * 方案版本状态：DRAFT 修订草稿（可编辑，revision 递增）；
 * PUBLISHED 已发布（每事件仅最大版本号者为活动版本）；MERGED 已并入（原分支，不可变）。
 */
public enum PlanVersionStatus {
    DRAFT,
    PUBLISHED,
    MERGED
}
