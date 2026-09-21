package com.example.starter.plan;

/**
 * 日计划状态：DRAFT 草稿 / PUBLISHED 已发布 / CANCELLED 已取消。
 */
public enum PlanStatus {
    /** 草稿：可整体替换占用，可发布。 */
    DRAFT,
    /** 已发布：时隙对外生效，不可再修改，可取消。 */
    PUBLISHED,
    /** 已取消：时隙立即释放，历史数据保留，不可再修改。 */
    CANCELLED
}
