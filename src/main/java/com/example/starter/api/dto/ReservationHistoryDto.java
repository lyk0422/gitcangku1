package com.example.starter.api.dto;

import java.util.List;

/**
 * 走廊预约历史视图：包含 ACTIVE 与 CANCELLED 的全部预约，取消记录保留。
 *
 * @param corridorId   走廊标识
 * @param count        全部预约数量
 * @param reservations 全部预约列表（按创建时间、业务键排序）
 */
public record ReservationHistoryDto(String corridorId, int count,
                                    List<ReservationResultDto> reservations) {
}
