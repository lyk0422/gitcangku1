package com.example.starter.api.dto;

/**
 * 起飞登记结果。
 *
 * @param occupancyId 占用唯一标识
 * @param departed    是否已起飞（登记成功恒为 true）
 * @param departedAt  起飞登记时间，epoch 毫秒（UTC）
 */
public record TakeoffResult(String occupancyId, boolean departed, long departedAt) {
}
