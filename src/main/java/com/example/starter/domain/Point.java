package com.example.starter.domain;

import java.math.BigDecimal;

/**
 * 二维平面上的坐标点，单位米，取值范围 [-100000,100000]，最多 3 位小数。
 * 构造时统一去掉多余的尾部零，保证同一坐标值的尺度一致（10、10.0、10.000 等价）。
 *
 * @param x X 轴坐标（米）
 * @param y Y 轴坐标（米）
 */
public record Point(BigDecimal x, BigDecimal y) {

    public Point {
        x = normalize(x);
        y = normalize(y);
    }

    /** 整数坐标便捷构造（单位米）。 */
    public Point(int x, int y) {
        this(BigDecimal.valueOf(x), BigDecimal.valueOf(y));
    }

    /**
     * 规范化坐标尺度：去掉尾部零并把负尺度归零，null 原样返回。
     * 例如 10.000 -> 10，100.001 保持不变。
     */
    public static BigDecimal normalize(BigDecimal value) {
        if (value == null) {
            return null;
        }
        BigDecimal stripped = value.stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
    }
}