package com.example.starter.snow.model;

import java.time.Instant;

/**
 * 读数勘误行：对旧读数只追加勘误与复核结论，不修改原读数。
 *
 * @param id 主键
 * @param readingId 关联读数主键
 * @param reviewer 复核人
 * @param note 勘误说明
 * @param requestId 幂等键
 * @param createdAt 入库时间（UTC）
 */
public record ErrataRec(long id, long readingId, String reviewer, String note,
                        String requestId, Instant createdAt) {
}