package com.example.starter.firmware.domain;

/**
 * 发布单区域带宽限流上限配置。
 *
 * @param releaseId   所属发布单ID
 * @param region      区域标识
 * @param maxInFlight 该区域同时进行中（已下发未完成）任务数上限，取值1~1000
 */
public record RegionLimit(long releaseId, String region, int maxInFlight) {
}
