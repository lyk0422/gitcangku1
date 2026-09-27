package com.example.starter.calibration.api.dto;

/**
 * 不确定度分量项。magnitude 为非负、最多 6 位小数的十进制字符串，与被测量同单位。
 *
 * @param name      分量名称；同一预算内唯一（区分大小写，按 trim 后比较）
 * @param magnitude 分量大小，非负十进制字符串
 */
public record ComponentRequest(String name, String magnitude) {
}
