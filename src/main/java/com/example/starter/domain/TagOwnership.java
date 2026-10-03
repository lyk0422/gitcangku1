package com.example.starter.domain;

import java.time.Instant;

/**
 * 标签登记与当前持有站点。
 *
 * @param tagCode 标签编号（合成，全局唯一）
 * @param stationCode 当前持有站点编号
 * @param registeredAt 登记时刻（UTC）
 */
public record TagOwnership(String tagCode, String stationCode, Instant registeredAt) {
}