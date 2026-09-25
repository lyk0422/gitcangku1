package com.example.starter.observation;

import java.time.LocalDateTime;

/**
 * 版本时间戳：用于置信度轨迹中 VERSION/INITIAL 事件点的定位。
 *
 * @param version   版本号
 * @param createdAt 该版本生成时间（服务器时区）
 */
public record VersionStamp(int version, LocalDateTime createdAt) {
}
