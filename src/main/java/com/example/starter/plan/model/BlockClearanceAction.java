package com.example.starter.plan.model;

/**
 * 封锁登记事务内对受影响计划实际执行的处理结果类型（不可变历史）。
 * CANCELLED：原计划被撤销；RESCHEDULED：原计划被整单改签，关联指向后继新计划。
 */
public enum BlockClearanceAction {
    CANCELLED,
    RESCHEDULED
}
