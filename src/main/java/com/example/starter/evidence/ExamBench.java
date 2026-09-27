package com.example.starter.evidence;

import java.time.LocalDateTime;

/**
 * 检验台实体，对应 exam_bench 表。
 *
 * @param id        主键
 * @param benchKey  检验台业务键，全局唯一
 * @param benchName 检验台名称
 * @param capacity  同一时段台位容量（可同时检验的证物数量上限）
 * @param createdAt 建档时间（Asia/Shanghai）
 */
public record ExamBench(
        Long id,
        String benchKey,
        String benchName,
        int capacity,
        LocalDateTime createdAt) {
}
