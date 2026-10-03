package com.example.starter.api.dto;

/**
 * 预测气象上传结果。
 *
 * @param forecastVersion 本批预测生效的全局气象（值班）版本，审查提交该版本
 * @param entryCount      本批预测条目数量（实际值）
 */
public record WeatherForecastUploadResult(long forecastVersion, int entryCount) {
}