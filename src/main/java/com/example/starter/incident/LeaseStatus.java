package com.example.starter.incident;

/**
 * 共享资源租约状态机：ACTIVE → RELEASED 或 ACTIVE → REVOKED。
 * 仅 ACTIVE 占用容量并允许任务开始；RELEASED（任务完成/取消）与 REVOKED（被抢占）均为终态。
 */
public enum LeaseStatus {

    /** 生效中，占用资源容量，同任务同资源至多一条。 */
    ACTIVE,

    /** 已释放：任务完成或取消时原子写入，不再占用容量。 */
    RELEASED,

    /** 已撤销：被更高优先级事件的抢占计划整单撤销，不再占用容量。 */
    REVOKED;
}
