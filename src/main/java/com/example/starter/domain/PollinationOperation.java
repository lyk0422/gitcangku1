package com.example.starter.domain;

import java.time.Instant;

/**
 * 授粉操作记录。
 *
 * @param id              主键
 * @param requestId       幂等请求键，全局唯一
 * @param requestHash     请求参数摘要，用于同键异参检测
 * @param inflorescenceId 花序 ID
 * @param pollenBatchNo   操作时登记的花粉批号
 * @param technician      执行技术员
 * @param executedAt      授粉执行时刻（UTC），须落在有效窗口内
 * @param status          操作状态
 * @param revokedAt       撤销时刻（UTC）；null 表示未撤销
 * @param createdAt       记录创建时刻（UTC）
 */
public record PollinationOperation(long id, String requestId, String requestHash,
                                   long inflorescenceId, String pollenBatchNo,
                                   String technician, Instant executedAt,
                                   OperationStatus status, Instant revokedAt,
                                   Instant createdAt) {
}