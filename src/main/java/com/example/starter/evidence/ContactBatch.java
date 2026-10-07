package com.example.starter.evidence;

import java.time.LocalDateTime;

/**
 * 接触批次实体，对应 contact_batch 表。只追加，事件序号全局递增。
 *
 * @param id          主键
 * @param batchKey    接触批次业务键（取登记请求 requestId）
 * @param eventSeq    接触事件序号，污染传播按此排序
 * @param custodianId 登记时全部成员的共同保管人
 * @param memberCount 成员数量（2 至 5）
 * @param createdAt   登记时刻（UTC）
 */
public record ContactBatch(
        Long id,
        String batchKey,
        long eventSeq,
        String custodianId,
        int memberCount,
        LocalDateTime createdAt) {
}
