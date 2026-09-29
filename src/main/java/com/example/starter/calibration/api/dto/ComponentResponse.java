package com.example.starter.calibration.api.dto;

/**
 * 不确定度分量响应项（按名称升序）。
 *
 * @param name      分量名称
 * @param magnitude 分量大小（非负，十进制字符串，最多 6 位小数）
 */
public record ComponentResponse(String name, String magnitude) {
}
