package com.example.starter.observation;

import java.time.LocalDateTime;

/**
 * 置信度轨迹中的一点：某个版本快照对应的置信度。
 *
 * @param version    版本号
 * @param confidence 该版本确定的置信度（0-100）
 * @param recordedAt 该版本生成时间（服务器时区）
 */
public record ConfidencePoint(int version, int confidence, LocalDateTime recordedAt) {
}
