package com.example.starter.batch;

/**
 * 召回处置分类（三选一，互斥）：
 * DESTROY 销毁，批次进入 DESTROYED 终态；
 * REWORK 返工，批次进入 REWORK_PENDING；
 * HOLD 保持隔离，批次状态不变但记录原因并递增版本。
 */
public enum DispositionCategory {
    DESTROY,
    REWORK,
    HOLD
}
