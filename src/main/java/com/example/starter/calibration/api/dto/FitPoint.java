package com.example.starter.calibration.api.dto;

/**
 * 拟合参考点：合成原值 x 与标准值 y，均为最多 6 位小数的十进制字符串。
 *
 * @param x 合成原值
 * @param y 标准值
 */
public record FitPoint(String x, String y) {
}
