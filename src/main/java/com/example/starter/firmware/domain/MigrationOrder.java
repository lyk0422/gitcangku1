package com.example.starter.firmware.domain;

/**
 * 跨队列迁移单主表。
 *
 * @param id           迁移单ID
 * @param migrationKey 迁移单业务键，全局唯一
 * @param releaseId    所属投放活动ID
 * @param status       迁移单状态
 * @param deviceCount  本单迁移设备数量，2~500
 * @param committedAt  提交时刻，UTC，ISO-8601
 */
public record MigrationOrder(long id, String migrationKey, long releaseId, MigrationStatus status,
                             int deviceCount, String committedAt) {
}
