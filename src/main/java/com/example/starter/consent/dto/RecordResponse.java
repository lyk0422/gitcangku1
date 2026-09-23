package com.example.starter.consent.dto;

/**
 * 记录响应：返回记录所属主体、用途、代次、记录键、内容与记录属性。
 *
 * @param subjectKey     主体标识（合成字符串）
 * @param purpose        用途代码
 * @param epoch          记录所属授权代次
 * @param recordKey      记录键
 * @param payload        记录内容（合成字符串）
 * @param attributeValue 记录属性取值，可能为空
 */
public record RecordResponse(String subjectKey, String purpose, int epoch, String recordKey,
                             String payload, String attributeValue) {
}
