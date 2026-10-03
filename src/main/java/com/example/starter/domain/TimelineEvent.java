package com.example.starter.domain;

import java.time.Instant;

/**
 * 时间线事件：由附着、摘除、交接与拒绝日志组合而成，按发生时刻排序。
 *
 * @param type 事件类型：ATTACH / DETACH / TRANSFER_INITIATED / TRANSFER_CONFIRMED /
 *     TRANSFER_CANCELLED / REJECTED
 * @param occurredAt 事件发生时刻（UTC）
 * @param tagCode 标签编号；与事件无关时为 null
 * @param animalCode 动物编号；与事件无关时为 null
 * @param stationCode 相关站点编号；与事件无关时为 null
 * @param fromStation 交接发起站点；非交接事件为 null
 * @param toStation 交接接收站点；非交接事件为 null
 * @param confirmedAt 交接确认时刻（UTC）；未确认或非交接事件为 null
 * @param reason 拒绝原因；仅 REJECTED 事件非空
 */
public record TimelineEvent(String type, Instant occurredAt, String tagCode, String animalCode,
        String stationCode, String fromStation, String toStation, Instant confirmedAt,
        String reason) {
}