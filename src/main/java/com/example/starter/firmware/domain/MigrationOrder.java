package com.example.starter.firmware.domain;

/**
 * 设备跨队列迁移单，整单原子生效；只在激活时落库。
 *
 * @param id           迁移单ID
 * @param migrationKey 迁移单业务键，全局唯一
 * @param campaignId   所属投放活动ID
 * @param status       状态：ACTIVATED 已激活
 * @param deviceCount  本单迁移设备数，取值2~500
 * @param requestId    激活请求的幂等键
 */
public record MigrationOrder(long id, String migrationKey, long campaignId, String status,
                             int deviceCount, String requestId) {
}
