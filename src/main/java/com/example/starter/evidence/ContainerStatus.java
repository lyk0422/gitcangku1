package com.example.starter.evidence;

/**
 * 封存容器状态。
 * SEALED 正常封存，可装载/移出证物、可借出与迁移；
 * INSPECTION_FAILED 巡检失败，集合冻结且证物禁止新借出与迁移，直至两名不同保管人完成复核封签。
 */
public enum ContainerStatus {
    SEALED,
    INSPECTION_FAILED
}
