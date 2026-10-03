package com.example.starter.domain;

/**
 * 花粉批号状态。
 */
public enum BatchStatus {
    /** 可用，允许登记授粉操作。 */
    ACTIVE,
    /** 已停用，登记授粉操作返回 422。 */
    DISABLED
}