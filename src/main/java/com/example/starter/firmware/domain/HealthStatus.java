package com.example.starter.firmware.domain;

/**
 * 设备健康状态：HEALTHY 健康；DOWN 停机升级中（波次成员）；UNHEALTHY 升级失败待人工恢复。
 */
public enum HealthStatus {
    HEALTHY,
    DOWN,
    UNHEALTHY
}
