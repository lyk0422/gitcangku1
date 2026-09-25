package com.example.starter.container;

/**
 * 封存容器状态。
 * SEALED 封存可用：可装载/移出证物、可继续巡检；
 * INSPECTION_FAILED 巡检失败：集合冻结、证物禁止新借出与迁移，直至两名不同保管人复核封签。
 */
public enum ContainerStatus {
    SEALED,
    INSPECTION_FAILED
}
