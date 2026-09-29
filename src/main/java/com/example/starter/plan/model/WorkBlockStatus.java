package com.example.starter.plan.model;

/**
 * 区段维护封锁状态：当前仅有 ACTIVE（已生效）。
 * 登记操作只有在全部相交已发布计划于同一事务内处理完毕后才写入 ACTIVE；
 * 登记失败不产生封锁记录，因此不存在中间持久化状态。
 */
public enum WorkBlockStatus {
    ACTIVE
}
