package com.example.starter.plan.model;

import java.time.Instant;

/**
 * 气象限速令：按区段与 UTC 左闭右开时段登记最高速度。
 * 同一 restrictionKey 修订产生新版本行，历史版本行不改写；
 * 同区段同时刻多条生效时以最低速度为准。
 *
 * @param id             主键
 * @param restrictionKey 限速令业务键，修订共享同一键
 * @param version        限速令版本，登记为 1，每次修订加一
 * @param sectionId      限速区段 ID
 * @param startUtc       限速开始时刻（含），UTC
 * @param endUtc         限速结束时刻（不含），UTC，必须晚于 startUtc
 * @param maxSpeedKmh    最高速度，单位 km/h，合法范围 10～300
 * @param status         限速令状态
 * @param operator       登记/修订操作者标识
 * @param createdAt      本版本登记时刻，UTC 毫秒
 */
public record WeatherRestriction(long id, String restrictionKey, int version, String sectionId,
                                 Instant startUtc, Instant endUtc, int maxSpeedKmh,
                                 RestrictionStatus status, String operator, long createdAt) {
}
