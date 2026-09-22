package com.example.starter.domain;

/**
 * 二维平面整数坐标点，单位米，取值范围 [-100000, 100000]。仅用于本题模拟，不用于真实飞行。
 */
public record Point(int x, int y) {

    public static final int MIN_COORD = -100000;
    public static final int MAX_COORD = 100000;

    /**
     * 判断坐标是否处于允许的闭区间内。
     */
    public static boolean inRange(int value) {
        return value >= MIN_COORD && value <= MAX_COORD;
    }
}
