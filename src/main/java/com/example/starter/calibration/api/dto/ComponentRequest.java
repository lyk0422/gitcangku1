package com.example.starter.calibration.api.dto;

/**
 * 不确定度分量请求项。值为非负、最多 6 位小数的十进制字符串；名称在同一预算内唯一。
 *
 * @param name  分量名称，非空，区分大小写，提交后保留原名
 * @param value 分量标准不确定度，非负十进制字符串
 */
public record ComponentRequest(String name, String value) {
}
