package com.example.starter.domain;

import java.time.Instant;

/**
 * 站点间标签交接单；只有接收站点确认后持有权才转移。
 *
 * @param id 交接单主键
 * @param tagCode 标签编号
 * @param fromStation 发起站点（交接时当前持有站点）
 * @param toStation 接收站点编号
 * @param status 状态：PENDING / CONFIRMED / CANCELLED
 * @param initiatedAt 发起时刻（UTC）
 * @param confirmedAt 接收站点确认时刻（UTC）；未确认为 null
 * @param cancelledAt 取消时刻（UTC）；未取消为 null
 * @param requestId 幂等请求键
 */
public record Transfer(long id, String tagCode, String fromStation, String toStation,
        TransferStatus status, Instant initiatedAt, Instant confirmedAt, Instant cancelledAt,
        String requestId) {
}