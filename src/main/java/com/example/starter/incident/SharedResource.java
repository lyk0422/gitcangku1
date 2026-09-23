package com.example.starter.incident;

import java.time.Instant;

/**
 * 跨事件共享资源实体，对应 shared_resources 表。
 * capacity 为正整数容量（单位：资源份额）；所有 ACTIVE 租约 quantity 之和永不超过容量。
 * 时间均为 UTC。
 */
public record SharedResource(
        long id,
        String resourceKey,
        String name,
        int capacity,
        Instant createdAt,
        Instant updatedAt) {
}
