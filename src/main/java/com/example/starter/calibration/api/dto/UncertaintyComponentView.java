package com.example.starter.calibration.api.dto;

/**
 * 不确定度分量视图。
 *
 * @param name  分量名称
 * @param value 分量数值（十进制字符串，非负）
 */
public record UncertaintyComponentView(String name, String value) {
}
