package com.example.starter.api.dto;

/**
 * 起飞标记结果。
 *
 * @param reservationId 预留标识
 * @param status        操作后状态（DEPARTED）
 */
public record DepartureResult(String reservationId, String status) {
}
