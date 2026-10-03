package com.example.starter.domain;

/**
 * 指派状态：ACTIVE 已指派未登轮；BOARDED 已登轮未离轮；COMPLETED 已离轮完成；
 * REVOKED 已撤销（仅未登轮可撤销，撤销即释放占用）。
 */
public enum AssignmentStatus {
    ACTIVE,
    BOARDED,
    COMPLETED,
    REVOKED
}