package com.example.starter.web;

import java.time.Instant;

/**
 * 花序视图（时间均为 UTC）。
 *
 * @param id 主键
 * @param code 花序业务编号
 * @param firstOpenAt 首次开放时刻，窗口起点
 * @param windowEndAt 窗口结束时刻（不含），等于起点 + 24 小时
 * @param windowOpen 查询时刻窗口是否仍处于有效期内
 * @param removedAt 摘除时刻，null 表示未摘除
 */
public record InflorescenceView(
        long id,
        String code,
        Instant firstOpenAt,
        Instant windowEndAt,
        boolean windowOpen,
        Instant removedAt) {
}