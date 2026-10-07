package com.example.starter.calibration.api.dto;

/**
 * 拟合参考点输入。x/y 为最多 6 位小数的十进制字符串。
 *
 * @param x 合成原值 x
 * @param y 标准值 y
 */
public record FitPointInput(String x, String y) {
}
