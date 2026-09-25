package com.example.starter.api.dto;

/**
 * 跑道登记/查询结果。
 *
 * @param runwayId       跑道唯一标识
 * @param version        当前跑道版本
 * @param hourlyCapacity 每 UTC 小时起降容量
 */
public record RunwayResult(String runwayId, int version, int hourlyCapacity) {
}
