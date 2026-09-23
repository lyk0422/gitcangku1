package com.example.starter.consent.dto;

/**
 * 记录响应：返回记录所属主体、用途、授权代次、目录代次、记录键、属性与内容。
 *
 * @param subjectKey        主体标识（合成字符串）
 * @param purpose           记录当前活动用途归属代码
 * @param epoch             记录所属授权代次
 * @param catalogGeneration 记录归属的目录代次
 * @param recordKey         记录键
 * @param recordAttribute   记录属性（处理空间内的整数值）
 * @param payload           记录内容（合成字符串）
 */
public record RecordResponse(String subjectKey,
                             String purpose,
                             int epoch,
                             long catalogGeneration,
                             String recordKey,
                             long recordAttribute,
                             String payload) {
}
