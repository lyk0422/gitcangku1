package com.example.starter.incident;

/**
 * 共享资源租约状态机：ACTIVE → RELEASED 或 ACTIVE → REVOKED。
 * RELEASED（任务完成/取消时原子释放）与 REVOKED（被更高严重级别事件抢占撤销）
 * 均为终态；状态流转时版本加 1。
 */
public enum LeaseStatus {

    /** 生效中；任务须持有 ACTIVE 租约才能标记 STARTED。 */
    ACTIVE,

    /** 已释放（任务完成或取消时原子释放）。 */
    RELEASED,

    /** 已撤销（被更高严重级别事件的抢占计划原子撤销）。 */
    REVOKED;
}
