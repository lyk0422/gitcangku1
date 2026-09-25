package com.example.starter.incident;

import java.time.Instant;

/**
 * 共享资源实体，对应 resources 表。
 * resourceKey 全局唯一；version 自 1 起，每次资质登记或提前撤销递增，
 * 租约指纹包含分配时刻的资源版本，资质变化后旧指纹的租约键不再命中。
 * 时间均为 UTC。
 */
public record Resource(
        long id,
        String resourceKey,
        int version,
        String createdBy,
        Instant createdAt,
        Instant updatedAt) {
}
