package com.example.starter.consent.dto;

/**
 * 快照内固化的单条记录。
 *
 * @param recordKey 记录键
 * @param payload   记录内容（合成字符串）
 */
public record SnapshotRecordItem(String recordKey, String payload) {
}
