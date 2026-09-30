package com.example.starter.batch;

/**
 * 仓储温度偏差类型。
 * OUT_OF_LIMIT：读数超出仓储段当时的温度上下限（等于限值不算越界）；
 * MISSING_READING：相邻读数采集时刻间隔严格大于 30 分钟（恰好 30 分钟不算缺测）。
 */
public enum DeviationType {
    OUT_OF_LIMIT,
    MISSING_READING
}
