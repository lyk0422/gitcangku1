package com.example.starter.incident;

import java.time.Instant;

/**
 * 共享资源定义实体，对应 shared_resources 表。
 * resourceKey 全局唯一；capacity 为正整数容量（单位数），
 * 该资源上 ACTIVE 租约的单位合计永不超过 capacity。时间均为 UTC。
 */
public record SharedResource(
        long id,
        String resourceKey,
        int capacity,
        String createdBy,
        Instant createdAt,
        Instant updatedAt) {
}
