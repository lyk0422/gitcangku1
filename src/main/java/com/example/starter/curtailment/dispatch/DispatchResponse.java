package com.example.starter.curtailment.dispatch;

import java.time.Instant;
import java.util.List;

/**
 * 削减调度响应。
 *
 * @param dispatchKey   调度业务键
 * @param feederId      馈线ID
 * @param executeFrom   执行区间起点（UTC，含）
 * @param executeTo     执行区间终点（UTC，不含）
 * @param targetPowerKw 目标削减功率，十进制字符串，单位 kW
 * @param version       当前版本
 * @param status        状态：DRAFT/PUBLISHED/CANCELLED
 * @param allocations   站点分配（取消后仍保留）
 * @param publishedAt   发布时间（UTC），未发布为 null
 * @param cancelledAt   取消时间（UTC），未取消为 null
 * @param createdAt     创建时间（UTC）
 */
public record DispatchResponse(String dispatchKey, String feederId, Instant executeFrom, Instant executeTo,
                               String targetPowerKw, int version, String status, List<AllocationView> allocations,
                               Instant publishedAt, Instant cancelledAt, Instant createdAt) {
}
