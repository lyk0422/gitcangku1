package com.example.starter.curtailment.dispatch;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 削减调度。
 *
 * @param id            主键
 * @param dispatchKey   调度业务键，全局唯一
 * @param feederId      馈线ID
 * @param executeFrom   执行区间起点（UTC，含）
 * @param executeTo     执行区间终点（UTC，不含）
 * @param targetPowerKw 目标削减功率，单位 kW，最多 3 位小数
 * @param version       乐观锁版本，从 1 开始
 * @param status        状态：DRAFT/PUBLISHED/CANCELLED
 * @param publishedAt   发布时间（UTC），未发布为 null
 * @param cancelledAt   取消时间（UTC），未取消为 null
 * @param createdAt     创建时间（UTC）
 * @param updatedAt     最近变更时间（UTC）
 */
public record Dispatch(long id, String dispatchKey, String feederId, Instant executeFrom, Instant executeTo,
                       BigDecimal targetPowerKw, int version, DispatchStatus status, Instant publishedAt,
                       Instant cancelledAt, Instant createdAt, Instant updatedAt) {
}
