package com.example.starter.plan.model;

/**
 * 封锁状态：EFFECTIVE 已生效。登记事务原子提交后封锁立即生效，
 * 登记失败时事务回滚、封锁不存在；本题不提供解除封锁操作。
 */
public enum WorkBlockStatus {
    EFFECTIVE
}
