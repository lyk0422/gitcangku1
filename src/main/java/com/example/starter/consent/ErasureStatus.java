package com.example.starter.consent;

/**
 * 清除任务状态：PENDING 待清除（可能已清部分批次）；DONE 已清完。只允许从 PENDING 变为 DONE。
 */
public enum ErasureStatus {
    PENDING,
    DONE
}
