package com.example.starter.repo;

/**
 * 豁免包持久化记录。一个 permitKey 精确绑定一个 routeVersion，
 * 签发后不可修改，只能整包撤销未使用余额。
 *
 * @param permitKey     豁免包唯一标识
 * @param routeVersion  绑定的精确航线版本
 * @param status        ISSUED / REVOKED
 * @param permitVersion 豁免包版本：不可变，恒为 1，审核快照冻结该版本
 * @param requestId     签发请求标识（唯一）
 * @param revokedAt    撤销时间，epoch 毫秒（UTC）；null 表示未撤销
 * @param createdAt     签发时间，epoch 毫秒（UTC）
 * @param touch         仅用于审核/撤销事务对该行加行级排他锁的计数器，无业务含义
 */
public record PermitPackagePo(String permitKey, int routeVersion, String status,
                              int permitVersion, String requestId, Long revokedAt,
                              long createdAt, long touch) {
}
