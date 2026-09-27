package com.example.starter.calibration.api.dto;

/**
 * 不确定度分量响应。
 *
 * @param name      分量名称
 * @param magnitude 分量大小（非负，十进制字符串）
 */
public record UncertaintyComponentResponse(String name, String magnitude) {
}
