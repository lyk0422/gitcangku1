package com.example.starter.api.dto;

/**
 * 预测气象上传结果。
 *
 * @param forecastVersion 本次上传生成的预测气象版本
 * @param entryCount      本次上传的条目数量
 */
public record ForecastUploadResult(Long forecastVersion, Integer entryCount) {
}
