package com.example.starter.batch.dto;

import java.time.Instant;

/**
 * 历史评分轨迹中单个终态批次落定后的评分快照：
 * scoreAfter 为该批次进入终态后、按当时最近 20 个终态批次滑动窗口计算的裁剪评分。
 */
public record ScoreTrajectoryPoint(String batchKey, String status, int contribution,
                                   int windowSize, int scoreAfter, Instant createdAt) {
}
