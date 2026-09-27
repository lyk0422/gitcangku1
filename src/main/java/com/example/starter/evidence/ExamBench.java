package com.example.starter.evidence;

import java.time.LocalDateTime;

/**
 * 检验台实体，对应 exam_bench 表。
 *
 * @param id        主键
 * @param benchKey  检验台业务键，全局唯一
 * @param capacity  同一时段（左闭右开区间）可承接的检验预约数上限（>=1）
 * @param createdAt 创建时间（Asia/Shanghai）
 */
public record ExamBench(
        Long id,
        String benchKey,
        Integer capacity,
        LocalDateTime createdAt) {
}
