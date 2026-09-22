package com.example.starter.domain;

/**
 * 二维平面上的整数坐标点，单位米，取值范围 [-100000,100000]。
 *
 * @param x X 轴坐标（米）
 * @param y Y 轴坐标（米）
 */
public record Point(int x, int y) {
}
