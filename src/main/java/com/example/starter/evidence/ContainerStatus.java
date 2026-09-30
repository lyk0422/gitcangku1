package com.example.starter.evidence;

/**
 * 容器状态。
 * NORMAL 正常；ABNORMAL 封条异常待复核（双人巡检确认封条异常后进入，复核放行回到 NORMAL）。
 */
public enum ContainerStatus {
    NORMAL,
    ABNORMAL
}
