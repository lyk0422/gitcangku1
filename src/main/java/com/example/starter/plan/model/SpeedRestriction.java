package com.example.starter.plan.model;

import java.time.Instant;

/**
 * 气象限速令：按区段与 UTC 左闭右开时段登记的最高速度命令。
 * 同一区段同一时刻可叠加多条，生效限速取最低速度。
 *
 * @param id              主键
 * @param restrictionKey  限速令业务键，全局唯一
 * @param sectionId       区段 ID
 * @param startUtc        限速开始时刻（含），UTC
 * @param endUtc          限速结束时刻（不含），UTC，必须晚于 startUtc
 * @param maxSpeedKmh     最高速度 km/h，合法范围 10～300
 * @param version         版本，登记为 1，每次修订加一
 * @param status          状态（ACTIVE/REVOKED）
 * @param operator        操作者标识，参与幂等指纹；null 表示未提供
 * @param createdAt       登记时刻，UTC 毫秒
 * @param updatedAt       最近变更时刻，UTC 毫秒
 */
public record SpeedRestriction(long id, String restrictionKey, String sectionId,
                               Instant startUtc, Instant endUtc, int maxSpeedKmh,
                               int version, RestrictionStatus status, String operator,
                               long createdAt, long updatedAt) {
}
