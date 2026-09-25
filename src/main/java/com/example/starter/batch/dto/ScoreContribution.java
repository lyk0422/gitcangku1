package com.example.starter.batch.dto;

import java.time.Instant;

/**
 * 滑动窗口内单个终态批次对供应商评分的贡献：RELEASED 记 +1，RECALLED 记 -10。
 */
public record ScoreContribution(String batchKey, String status, int contribution,
                                Instant createdAt) {
}
