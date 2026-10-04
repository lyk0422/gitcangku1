package com.example.starter.domain;

import java.math.BigDecimal;

/**
 * 定时航线航点：平面坐标（单位米，最多 3 位小数）与 UTC 整秒到达时刻。
 *
 * @param x                  X 轴坐标（米），范围 [-100000,100000]，最多 3 位小数
 * @param y                  Y 轴坐标（米），范围 [-100000,100000]，最多 3 位小数
 * @param arrivalEpochSecond 到达时刻，UTC 整秒的 epoch 秒
 */
public record TimedPoint(BigDecimal x, BigDecimal y, long arrivalEpochSecond) {

    /** X 坐标换算为千分之一米（调用前须保证小数位不超过 3 位）。 */
    public long xMilli() {
        return x.movePointRight(3).longValueExact();
    }

    /** Y 坐标换算为千分之一米（调用前须保证小数位不超过 3 位）。 */
    public long yMilli() {
        return y.movePointRight(3).longValueExact();
    }
}