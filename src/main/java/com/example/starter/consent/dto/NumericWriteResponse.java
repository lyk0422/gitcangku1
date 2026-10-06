package com.example.starter.consent.dto;

/**
 * 数值/派生写入响应：仅返回记录标识与代次，不回值；值只在重新鉴权的读取中返回。
 *
 * @param subjectKey 目标主体标识（合成字符串）
 * @param epoch      记录所属授权代次
 * @param recordKey  记录键
 */
public record NumericWriteResponse(String subjectKey, int epoch, String recordKey) {
}
