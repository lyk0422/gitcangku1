package com.example.starter.maintenance.domain;

/**
 * 隔离令锁定点（创建时固化）。
 *
 * @param orderId       所属隔离令标识
 * @param lockPointId   锁定点标识，隔离令内唯一
 * @param ordinal       提交序号（从 0 起），用于明细稳定排序
 * @param name          锁定点名称/位置描述，可为 null
 */
public record IsolationLockPoint(
        String orderId,
        String lockPointId,
        int ordinal,
        String name) {
}
