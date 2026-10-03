package com.example.coral.domain;

import java.time.Instant;

/**
 * 复核记录：每次复核追加一条，按时间构成复核链。
 *
 * @param id 复核记录主键
 * @param requestId 幂等请求编号
 * @param transplantId 被复核的移植记录ID
 * @param result 复核结果：ALIVE存活/DEAD死亡/PENDING待复核
 * @param note 复核备注，可空
 * @param reviewedAt 复核时刻（UTC）
 */
public record Review(long id, String requestId, long transplantId, PlantStatus result,
        String note, Instant reviewedAt) {
}