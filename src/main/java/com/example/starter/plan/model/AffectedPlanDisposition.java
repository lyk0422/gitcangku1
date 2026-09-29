package com.example.starter.plan.model;

/**
 * 封锁登记时对单个受影响已发布计划的处理结果。
 * CANCELLED 撤销该计划；RESCHEDULED 将该计划整单改签为不相交的新计划。
 */
public enum AffectedPlanDisposition {
    CANCELLED,
    RESCHEDULED
}
