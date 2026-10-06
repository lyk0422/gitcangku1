package com.example.starter.incident;

import java.time.Instant;

/**
 * 可租约资源实体，对应 leased_resources 表。
 * resourceKey 全局唯一，首次分配时自动注册；activeLeaseId 指向当前活动租约，
 * 为空表示资源空闲；同一资源至多一条活动租约，杜绝并发续期/分配产生重叠授权。
 */
public record LeasedResource(
        long id,
        String resourceKey,
        Long activeLeaseId,
        Instant createdAt) {
}
