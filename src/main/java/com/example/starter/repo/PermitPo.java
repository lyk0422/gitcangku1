package com.example.starter.repo;

/**
 * 豁免包持久化记录。
 *
 * @param permitId     豁免包唯一标识 permitKey
 * @param routeId      绑定的航线标识
 * @param routeVersion 绑定的精确航线版本
 * @param status       ACTIVE / REVOKED
 * @param version      豁免包版本：签发为 1，撤销推进为 2
 * @param requestId    签发操作的请求标识
 * @param issuedAt     签发时间（epoch 毫秒，UTC）
 * @param revokedAt    撤销时间（epoch 毫秒，UTC）；null 表示未撤销
 */
public record PermitPo(String permitId, String routeId, int routeVersion, String status,
                       int version, String requestId, long issuedAt, Long revokedAt) {
}
