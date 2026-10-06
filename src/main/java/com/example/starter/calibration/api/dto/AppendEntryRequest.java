package com.example.starter.calibration.api.dto;

/**
 * 会话追加条目请求。rawValue 为最多 6 位小数的十进制字符串；
 * 首条必须显式给出 direction（UP/DOWN），后续条目的 direction 字段被忽略（按读数增减推导、相同继承）。
 *
 * @param requestId              幂等请求 ID
 * @param expectedSessionVersion 期望会话版本，须等于当前版本，成功后加一
 * @param rawValue               原始值，十进制字符串
 * @param direction              方向 UP/DOWN；仅首条必填
 * @param lowerLimit             合格下限（含端点），十进制字符串
 * @param upperLimit             合格上限（含端点），十进制字符串
 * @param submittedBy            条目提交人
 */
public record AppendEntryRequest(
        String requestId,
        Long expectedSessionVersion,
        String rawValue,
        String direction,
        String lowerLimit,
        String upperLimit,
        String submittedBy) {
}
