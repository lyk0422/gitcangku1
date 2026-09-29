package com.example.starter.calibration.api.dto;

/**
 * 追加仪器不确定度阈值版本请求。新版本 valid_from 必须严格晚于该仪器上一版本，
 * 与上一版本构成左闭右开的生效区间；阈值版本一经创建不改写。
 *
 * @param instrumentId 仪器 ID
 * @param validFrom    生效起点（UTC，ISO-8601，左闭）
 * @param threshold    合成不确定度允许上限（非负），最多 6 位小数字符串
 */
public record CreateThresholdRequest(String instrumentId, String validFrom, String threshold) {
}
