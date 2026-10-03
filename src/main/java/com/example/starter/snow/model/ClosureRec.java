package com.example.starter.snow.model;

import java.time.Instant;

/**
 * 封闭命令行。
 *
 * @param id 主键
 * @param regionId 控制区标识
 * @param source MANUAL=人工，AUTO=超限自动触发
 * @param reason 封闭原因
 * @param requestId 幂等键，自动触发为 null
 * @param createdAt 生成时间（UTC）
 */
public record ClosureRec(long id, String regionId, String source, String reason,
                         String requestId, Instant createdAt) {

    public static final String MANUAL = "MANUAL";
    public static final String AUTO = "AUTO";
}