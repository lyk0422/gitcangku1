package com.example.starter.api.dto;

/**
 * 预测气象上传结果。
 *
 * @param forecastVersion 本批预测分配的全局预测版本
 * @param entryCount      本批写入的预测条目数
 */
public record ForecastUploadResult(Long forecastVersion, Integer entryCount) {
}
