package com.example.starter.calibration.api.dto;

/**
 * 不确定度分量视图。
 *
 * @param name  分量名称
 * @param value 分量值（十进制字符串）
 */
public record ComponentView(String name, String value) {
}
