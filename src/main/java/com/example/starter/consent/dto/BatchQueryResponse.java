package com.example.starter.consent.dto;

import java.time.Instant;
import java.util.List;

import com.example.starter.consent.Purpose;

/**
 * 批量查询成功响应：整批通过门禁后生成的不可改写快照。
 *
 * @param batchId      批次标识
 * @param recipientId  接收方标识
 * @param purpose      查询用途
 * @param recordKey    记录键
 * @param createdAt    快照生成时刻（UTC）
 * @param items        每个主体的快照记录（含授权代次与证明版本）
 */
public record BatchQueryResponse(String batchId, String recipientId, Purpose purpose,
                                 String recordKey, Instant createdAt, List<SnapshotItem> items) {
}
