package com.example.starter.api.dto;

import java.util.List;

/**
 * 走廊时段查询视图：返回与查询窗 [from,to) 有任意时间重叠的生效预约。
 * 只读，不改变状态。
 *
 * @param corridorId   走廊标识
 * @param from         查询窗开始时刻，epoch 毫秒（UTC），含
 * @param to           查询窗结束时刻，epoch 毫秒（UTC），不含
 * @param count        与查询窗重叠的生效预约数量
 * @param reservations 生效预约列表（按开始时刻、业务键排序）
 */
public record ReservationWindowDto(String corridorId, long from, long to, int count,
                                   List<ReservationResultDto> reservations) {
}
