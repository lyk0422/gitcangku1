package com.example.starter.calibration.api.dto;

/**
 * 不确定度分量贡献度（诊断用）。
 *
 * @param name    分量名称
 * @param value   分量数值（十进制字符串）
 * @param squared 分量平方（十进制字符串）
 * @param share   占平方和的比例（HALF_UP 6 位小数；平方和为 0 时为 "0"）
 */
public record ComponentContribution(String name, String value, String squared, String share) {
}
