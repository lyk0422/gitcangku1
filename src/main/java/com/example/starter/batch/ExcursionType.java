package com.example.starter.batch;

/**
 * 仓储温度偏差类型。
 * OUT_OF_RANGE 读数温度超出当时生效的上下限；
 * MISSING_INTERVAL 与同仓储段前一读数的采集时刻间隔超过 30 分钟（恰好 30 分钟不算间断）。
 */
public enum ExcursionType {
    OUT_OF_RANGE,
    MISSING_INTERVAL
}
