package com.example.starter.api.dto;

import java.util.List;

/**
 * 走廊容量占用视图。按任意查询时刻返回当时生效（startTime &lt;= at &lt; endTime）
 * 的预约列表与数量；只读，不改变任何状态。
 *
 * @param corridorId 走廊标识
 * @param at         查询时刻，epoch 毫秒（UTC）
 * @param capacity   走廊当前容量上限
 * @param count      该时刻生效预约数量
 * @param reservations 该时刻生效预约列表（按开始时刻、业务键排序）
 */
public record OccupancyView(String corridorId, long at, int capacity, int count,
                            List<ReservationResultDto> reservations) {
}
