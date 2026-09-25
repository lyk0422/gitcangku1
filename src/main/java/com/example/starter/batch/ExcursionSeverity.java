package com.example.starter.batch;

/**
 * 储运温度偏差严重级别。
 * 实测最低/最高温任一边界超出批次温度规格即为 MAJOR；两侧均在规格内（含边界）为 MINOR。
 */
public enum ExcursionSeverity {
    MAJOR,
    MINOR
}
