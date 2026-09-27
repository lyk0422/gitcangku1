package com.example.starter.firmware.domain;

/**
 * 标签字典修订历史（只增不改）。
 *
 * @param revision       修订版本号，等于提交后字典版本，从 1 开始
 * @param operator       提交本批修改的操作者
 * @param requestedCount 本批要求修改的设备项数（要求值）
 * @param changedCount   本批标签实际发生变化的设备项数（实际值），requestedCount-changedCount 为无变化差额
 * @param changedAtUtc   修订提交时刻，UTC，ISO-8601
 */
public record TagRevision(long revision, String operator, int requestedCount, int changedCount,
                          String changedAtUtc) {
}
