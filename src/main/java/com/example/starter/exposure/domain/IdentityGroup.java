package com.example.starter.exposure.domain;

/**
 * 身份归并组 PO。一组共享每访客每日上限的合成访客集合；组关系全局共享，
 * 不按公告区分。历史预占/账目的原申请编号归属不因归并改变。
 *
 * @param groupId      归并组编号
 * @param version      乐观版本号：创建为 1，每次成员变更 +1；归并请求须携带期望版本
 * @param createdAtUtc 组创建时刻，epoch 毫秒，UTC
 */
public record IdentityGroup(
        String groupId,
        int version,
        long createdAtUtc
) {
}
