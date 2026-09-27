package com.example.starter.api.dto;

/**
 * 起飞登记结果。时间精度 epoch 毫秒（UTC）。
 *
 * @param occupancyId  占用标识
 * @param status       占用状态（ACTIVE 才能起飞）
 * @param departed     是否已起飞（true）
 * @param departedAt   起飞时间 epoch 毫秒（UTC）
 */
public record DepartureResult(String occupancyId, String status, boolean departed,
                              long departedAt) {
}
