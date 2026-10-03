package com.example.starter.domain;

import java.time.Instant;

/**
 * 授粉结果确认记录。
 *
 * @param id          主键
 * @param requestId   幂等请求键，全局唯一
 * @param requestHash 请求参数摘要，用于同键异参检测
 * @param operationId 授粉操作 ID，一个操作只允许一条确认
 * @param result      确认结果：SUCCESS / FAILED
 * @param technician  确认技术员
 * @param confirmedAt 结果确认时刻（UTC），须在操作后 72 小时内
 * @param createdAt   记录创建时刻（UTC）
 */
public record PollinationConfirmation(long id, String requestId, String requestHash,
                                      long operationId, ConfirmationResult result,
                                      String technician, Instant confirmedAt,
                                      Instant createdAt) {
}