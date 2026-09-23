package com.example.starter.batch;

/**
 * 召回处置分类（三者互斥，闭包恰好划分一次）：
 * DESTROY 销毁，批次进入 DESTROYED 终态；
 * REWORK 返工，批次进入 REWORK_PENDING；
 * HOLD 暂挂隔离，批次状态保持不变并记录原因。
 */
public enum DispositionCategory {
    DESTROY,
    REWORK,
    HOLD
}
