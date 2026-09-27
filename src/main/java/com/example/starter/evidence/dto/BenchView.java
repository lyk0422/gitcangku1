package com.example.starter.evidence.dto;

import java.time.LocalDateTime;

/**
 * 检验台视图。
 *
 * @param benchKey  检验台业务键
 * @param name      检验台名称
 * @param capacity  台位容量：同一左闭右开时段内最多并行的检验数量
 * @param createdAt 注册时间（Asia/Shanghai）
 */
public record BenchView(
        String benchKey,
        String name,
        int capacity,
        LocalDateTime createdAt) {
}
