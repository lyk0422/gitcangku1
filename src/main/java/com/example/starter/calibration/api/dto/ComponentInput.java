package com.example.starter.calibration.api.dto;

/**
 * 不确定度分量入参。
 *
 * @param name  分量名称（同一请求内唯一）
 * @param value 分量数值（非负十进制字符串，最多 6 位小数）
 */
public record ComponentInput(String name, String value) {
}
