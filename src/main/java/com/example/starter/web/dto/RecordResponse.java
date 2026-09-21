package com.example.starter.web.dto;

/**
 * 记录写入/查询响应。
 *
 * @param subjectKey 主体标识
 * @param purpose    用途
 * @param epoch      记录所属授权代次
 * @param recordKey  记录键
 * @param payload    记录内容
 */
public record RecordResponse(String subjectKey, String purpose, int epoch,
                             String recordKey, String payload) {
}
