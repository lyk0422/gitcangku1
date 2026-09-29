package com.example.starter.calibration.api.dto;

/**
 * 不确定度分量请求项。
 *
 * @param name      分量名称，非空；同一预算内不可重复
 * @param magnitude 分量大小（非负，含 0），最多 6 位小数字符串
 */
public record ComponentRequest(String name, String magnitude) {
}
