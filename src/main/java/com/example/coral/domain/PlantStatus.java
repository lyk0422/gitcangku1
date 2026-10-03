package com.example.coral.domain;

/**
 * 植株状态：PENDING待复核 / ALIVE存活 / DEAD死亡。
 * 死亡后释放样方容量，但移植记录保留；死亡植株不得再标为存活。
 */
public enum PlantStatus {
    PENDING,
    ALIVE,
    DEAD
}