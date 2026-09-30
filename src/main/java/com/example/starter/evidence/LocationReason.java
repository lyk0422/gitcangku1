package com.example.starter.evidence;

/**
 * 证物历史位置轨迹原因。
 * INTAKE 入库；PLACED 放入容器；TRANSFER_ACCEPT 交接接受；
 * INSPECTION_QUARANTINE 容器异常双人确认后隔离（轨迹保留原位置与原保管人）；
 * REVIEW_RELEASE 复核放行。
 */
public enum LocationReason {
    INTAKE,
    PLACED,
    TRANSFER_ACCEPT,
    INSPECTION_QUARANTINE,
    REVIEW_RELEASE
}
