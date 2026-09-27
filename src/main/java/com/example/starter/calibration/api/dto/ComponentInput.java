package com.example.starter.calibration.api.dto;

/**
 * 不确定度分量输入。
 *
 * @param name  分量名称（同一测量内唯一）
 * @param value 分量值（非负十进制字符串，最多 6 位小数）
 */
public record ComponentInput(String name, String value) {
}
