package com.example.starter.consent.dto;

import java.time.Instant;
import java.util.List;

import com.example.starter.consent.Purpose;

/**
 * 批次查询详情响应：可查询批次状态；通过门禁时返回快照（含所用证明版本），
 * 被门禁拒绝时返回稳定的阻断明细（主体与原因）。
 *
 * @param batchId     批次标识
 * @param recipientId 接收方标识
 * @param purpose     查询用途
 * @param recordKey   记录键
 * @param status      状态：SNAPSHOTTED 已生成快照 / BLOCKED 门禁拒绝
 * @param createdAt   创建时刻（UTC）
 * @param items       快照条目，status=SNAPSHOTTED 时非空
 * @param blocks      阻断明细，status=BLOCKED 时非空
 */
public record BatchDetailResponse(String batchId, String recipientId, Purpose purpose, String recordKey,
                                  String status, Instant createdAt,
                                  List<SnapshotItem> items, List<BatchBlockDetail> blocks) {
}
