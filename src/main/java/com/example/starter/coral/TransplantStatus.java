package com.example.starter.coral;

/**
 * 移植体当前状态：ALIVE=存活，DEAD=死亡（终态，释放样方容量），PENDING=待复核。
 */
public enum TransplantStatus {
    ALIVE,
    DEAD,
    PENDING
}