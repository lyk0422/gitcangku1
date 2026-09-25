package com.example.starter.batch;

/**
 * 储运偏差裁决状态：OPEN 未裁决（持续参与放行门禁）；ADJUDICATED 已裁决（快照不可变）。
 */
public enum ExcursionStatus {
    OPEN,
    ADJUDICATED
}
