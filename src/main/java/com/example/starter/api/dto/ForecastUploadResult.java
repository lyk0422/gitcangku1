package com.example.starter.api.dto;

/**
 * 预测气象上传结果。
 *
 * @param forecastVersion 系统分配的预测气象版本
 * @param entryCount      本次固化的条目数量
 */
public record ForecastUploadResult(long forecastVersion, int entryCount) {
}