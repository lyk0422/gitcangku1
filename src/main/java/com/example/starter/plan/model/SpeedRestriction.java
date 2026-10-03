package com.example.starter.plan.model;

import java.time.Instant;

/**
 * 气象限速令：按区段与 UTC 左闭右开时段 [startUtc, endUtc) 登记最高速度。
 * 同一区段同一时刻可叠加多条限速令，生效速度取最低值。
 *
 * @param id              主键
 * @param restrictionKey  限速令业务键，全局唯一
 * @param sectionId       限速区段 ID
 * @param startUtc        限速开始时刻（含），UTC
 * @param endUtc          限速结束时刻（不含），UTC，必须晚于 startUtc
 * @param maxSpeedKmh     最高速度，单位 km/h，合法范围 10~300
 * @param version         限速令版本，初始 1，每次修订加一
 * @param status          限速令状态
 * @param operator        登记或最近修订的操作者标识
 * @param createdAt       创建时刻，UTC 毫秒
 * @param updatedAt       最近变更时刻，UTC 毫秒
 */
public record SpeedRestriction(long id, String restrictionKey, String sectionId,
                               Instant startUtc, Instant endUtc, int maxSpeedKmh,
                               int version, RestrictionStatus status, String operator,
                               long createdAt, long updatedAt) {
}