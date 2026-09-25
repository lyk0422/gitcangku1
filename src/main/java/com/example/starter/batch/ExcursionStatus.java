package com.example.starter.batch;

/**
 * 储运偏差裁决状态。
 * OPEN：未裁决；MAJOR OPEN 直接阻断放行，MINOR OPEN 需质控确认后放行；
 * CONFIRMED：MINOR 偏差已经质控确认，解除其放行门禁；
 * ADJUDICATED：MAJOR 偏差已裁决（REWORK/REJECT），裁决快照不可变。
 */
public enum ExcursionStatus {
    OPEN,
    CONFIRMED,
    ADJUDICATED
}
