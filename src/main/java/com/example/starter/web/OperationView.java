package com.example.starter.web;

import java.time.Instant;

/**
 * 授粉操作视图（时间均为 UTC）。
 *
 * @param id 主键
 * @param requestId 授粉幂等请求键
 * @param inflorescenceId 所属花序 id
 * @param batchNo 花粉批号快照
 * @param technicianId 执行技术员编号
 * @param operatedAt 执行时刻
 * @param status 操作状态：EFFECTIVE/REVOKED/CONFIRMED_SUCCESS/CONFIRMED_FAILED
 * @param revokedAt 撤销时刻，null 表示未撤销
 * @param confirmedAt 结果确认时刻，null 表示未确认
 * @param confirmedBatchNo 确认成功时固化的花粉批号，未确认成功为 null
 */
public record OperationView(
        long id,
        String requestId,
        long inflorescenceId,
        String batchNo,
        String technicianId,
        Instant operatedAt,
        String status,
        Instant revokedAt,
        Instant confirmedAt,
        String confirmedBatchNo) {
}