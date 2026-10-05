package com.example.starter.plan.model;

import java.time.Instant;

/**
 * 气象限速令的一个版本，区间左闭右开 [startUtc, endUtc)。
 * 同一 restrictionKey 修订产生新版本，旧版本置为 SUPERSEDED，历史版本保留不改写；
 * 同一区段同一时刻多条生效限速叠加时以最低速度为准。
 *
 * @param id             主键
 * @param restrictionKey 限速令业务键，同一令的各版本共用
 * @param version        版本号，登记为 1，每次修订加一
 * @param sectionId      限速区段 ID
 * @param startUtc       限速开始时刻（含），UTC
 * @param endUtc         限速结束时刻（不含），UTC，必须晚于 startUtc
 * @param maxSpeedKmh    最高速度，单位 km/h，合法范围 10～300
 * @param status         限速令状态
 * @param operator       操作者标识，参与幂等指纹
 * @param createdAt      该版本登记时刻，UTC 毫秒
 */
public record SpeedRestriction(long id, String restrictionKey, int version, String sectionId,
                               Instant startUtc, Instant endUtc, int maxSpeedKmh,
                               RestrictionStatus status, String operator, long createdAt) {
}
