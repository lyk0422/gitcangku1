package com.example.starter.plan.model;

/**
 * 容量交换单状态：PREVIEW 仅预览未生效；ACTIVATED 已在一个事务内完成全部占用替换并写入前后快照，
 * 此后不可变，重复激活按幂等重放首次结果。
 */
public enum CapacitySwapStatus {
    PREVIEW,
    ACTIVATED
}
