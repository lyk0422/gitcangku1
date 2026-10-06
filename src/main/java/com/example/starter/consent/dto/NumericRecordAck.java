package com.example.starter.consent.dto;

/**
 * 数值写入与派生响应：仅回记录键与代次，值只在重新鉴权的读取中返回。
 *
 * @param recordKey 记录键
 * @param epoch     记录所属研究授权代次
 */
public record NumericRecordAck(String recordKey, int epoch) {
}
