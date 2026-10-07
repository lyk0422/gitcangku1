package com.example.starter.consent;

/**
 * 清除任务状态：PENDING 待清除（含清除中）；DONE 该代次旧载荷已全部物理清除。
 * 只允许从 PENDING 变为 DONE。
 */
public enum ErasureStatus {
    PENDING,
    DONE
}
