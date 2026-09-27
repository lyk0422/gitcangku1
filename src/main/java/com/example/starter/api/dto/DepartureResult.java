package com.example.starter.api.dto;

/**
 * 起飞登记结果。
 *
 * @param occupancyId 占用标识
 * @param status      登记后状态：DEPARTED
 * @param departedMs  实际起飞登记时刻，epoch 毫秒 UTC
 */
public record DepartureResult(String occupancyId, String status, long departedMs) {
}
