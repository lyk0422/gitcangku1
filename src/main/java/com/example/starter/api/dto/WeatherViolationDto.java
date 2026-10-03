package com.example.starter.api.dto;

/**
 * 气象违规明细：预测值低于区域最低标准。给出实际值、要求值与差额。
 *
 * @param zoneId         区域标识
 * @param windowStartUtc 时段起点（含），epoch 毫秒（UTC）
 * @param windowEndUtc   时段终点（不含），epoch 毫秒（UTC）
 * @param field          违规字段：visibility 能见度 / cloudBase 云底高度
 * @param actual         预测实际值（米）
 * @param required       标准要求值（米）
 * @param deficit        差额（required - actual，正数，米）
 */
public record WeatherViolationDto(String zoneId, long windowStartUtc, long windowEndUtc,
                                  String field, int actual, int required, int deficit) {
}