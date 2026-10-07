package com.example.starter.calibration.api.dto;

/**
 * 冻结的参考点及其残差（十进制字符串，精确值）。
 *
 * @param x        合成原值
 * @param y        标准值
 * @param residual 残差 y−(a·x+b)，精确值
 */
public record FitPointResult(String x, String y, String residual) {
}
