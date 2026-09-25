package com.example.starter.api.dto;

/**
 * 跑道登记结果。
 *
 * @param runwayId        跑道标识
 * @param version         当前跑道版本（初始 1）
 * @param capacityPerHour 每小时起降容量（架次）
 */
public record RunwayResult(String runwayId, int version, int capacityPerHour) {
}
