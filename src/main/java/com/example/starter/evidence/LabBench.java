package com.example.starter.evidence;

import java.time.LocalDateTime;

/**
 * 检验台实体，对应 lab_bench 表。检验台为可复用资源；capacity 为同一时段可并行检验的台位数量。
 * 台位业务字段注册后不可变。
 *
 * @param id        主键
 * @param benchKey  检验台业务键，全局唯一
 * @param name      检验台名称
 * @param capacity  台位容量（同一左闭右开时段内最多并行的检验数量），正整数
 * @param createdAt 注册时间（Asia/Shanghai）
 */
public record LabBench(
        Long id,
        String benchKey,
        String name,
        int capacity,
        LocalDateTime createdAt) {
}
