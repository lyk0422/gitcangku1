package com.example.starter.evidence.dto;

import java.time.LocalDateTime;

/**
 * 检查点视图。
 *
 * @param checkpointNo 检查点号
 * @param deadlineAt   该点 UTC 截止时刻
 */
public record CheckpointView(
        int checkpointNo,
        LocalDateTime deadlineAt) {
}