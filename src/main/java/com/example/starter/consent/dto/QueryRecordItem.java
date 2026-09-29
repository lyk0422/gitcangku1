package com.example.starter.consent.dto;

/**
 * 查询结果中的单个对象项。
 *
 * @param recordKey 对象键
 * @param payload   对象内容（合成字符串）
 */
public record QueryRecordItem(String recordKey, String payload) {
}
