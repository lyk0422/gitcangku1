package com.example.starter.consent;

/**
 * 旧代载荷清除任务状态：PENDING 待清除；DONE 已完成（本代无未清项）。
 * 只允许从 PENDING 变为 DONE。
 */
public enum ErasureTaskStatus {
    PENDING,
    DONE
}
