package com.example.starter.evidence;

/**
 * 容器巡检状态。
 * PENDING 已有一人确认待第二人；CONFIRMED 两名不同保管人已确认同一容器版本。
 */
public enum InspectionStatus {
    PENDING,
    CONFIRMED
}
