package com.example.starter.domain;

import java.time.Instant;

/**
 * 授粉操作及结果确认。同一花序同一窗口仅允许一条有效操作（数据库唯一约束保证）。
 *
 * @param id 主键
 * @param requestId 授粉操作幂等请求键
 * @param requestHash 授粉请求参数摘要，用于同键异参检测
 * @param inflorescenceId 所属花序 id
 * @param pollenBatchId 花粉批次 id（快照）
 * @param batchNo 花粉批号快照
 * @param technicianId 执行技术员编号
 * @param operatedAt 授粉执行时刻（UTC），须落在花序窗口内
 * @param status 操作状态，见 {@link OperationStatus}
 * @param revokedAt 撤销时刻（UTC），null 表示未撤销
 * @param confirmRequestId 结果确认幂等请求键，null 表示尚未确认
 * @param confirmRequestHash 结果确认请求参数摘要，null 表示尚未确认
 * @param confirmedAt 结果确认时刻（UTC），须在执行后 72 小时内；null 表示未确认
 * @param confirmedBatchNo 确认成功时固化的花粉批号；未确认成功为 null
 * @param createdAt 记录创建时刻（UTC）
 */
public record PollinationOperation(
        long id,
        String requestId,
        String requestHash,
        long inflorescenceId,
        long pollenBatchId,
        String batchNo,
        String technicianId,
        Instant operatedAt,
        OperationStatus status,
        Instant revokedAt,
        String confirmRequestId,
        String confirmRequestHash,
        Instant confirmedAt,
        String confirmedBatchNo,
        Instant createdAt) {

    /**
     * 操作状态枚举：EFFECTIVE 之后只能进入唯一终态（撤销或确认之一）。
     */
    public enum OperationStatus {
        /** 有效，等待结果确认。 */
        EFFECTIVE,
        /** 已撤销（终态），花序在窗口内可重新授粉。 */
        REVOKED,
        /** 已确认成功（终态），花粉批号已固化，不可撤销。 */
        CONFIRMED_SUCCESS,
        /** 已确认失败（终态），不再占用花序。 */
        CONFIRMED_FAILED
    }
}