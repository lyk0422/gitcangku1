package com.example.starter.calibration.api.dto;

/**
 * 拟合参考点响应（含冻结残差评分）。
 *
 * @param x        合成原值 x（十进制字符串）
 * @param y        标准值 y（十进制字符串）
 * @param residual 冻结残差评分 y-(a×x+b)（十进制字符串）
 */
public record FitPointResponse(String x, String y, String residual) {
}
