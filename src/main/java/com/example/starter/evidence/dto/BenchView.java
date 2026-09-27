package com.example.starter.evidence.dto;

import java.time.LocalDateTime;

/**
 * 检验台视图。
 *
 * @param benchKey  检验台业务键
 * @param capacity  同一时段可承接的检验预约数上限
 * @param createdAt 创建时间（Asia/Shanghai）
 */
public record BenchView(
        String benchKey,
        Integer capacity,
        LocalDateTime createdAt) {
}
