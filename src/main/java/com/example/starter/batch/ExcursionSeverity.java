package com.example.starter.batch;

/**
 * 储运偏差严重级别。
 * MINOR：偏差实测温度区间完全落在批次储运规格内；
 * MAJOR：偏差任一边界（实测最低温或最高温）超过批次规格上下限。
 */
public enum ExcursionSeverity {
    MINOR,
    MAJOR
}
