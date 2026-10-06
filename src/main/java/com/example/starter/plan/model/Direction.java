package com.example.starter.plan.model;

/**
 * 单线区段行车方向：FORWARD 正向 / REVERSE 反向。
 * 相邻反向占用要求整秒清空间隔；同向仅遵守原有时隙互斥。
 */
public enum Direction {
    FORWARD,
    REVERSE
}
