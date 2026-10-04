package com.example.starter.plan.model;

import java.time.Instant;

/**
 * 铁路气象限速令。按区段与 UTC 左闭右开时段 [startUtc, endUtc) 登记最高速度；
 * 同一区段同一时刻可叠加多条生效限速令，裁决时以最低速度为准。
 *
 * @param id           主键
 * @param restrictionKey 限速令业务键，全局唯一，撤销后仍保留历史
 * @param version      限速令版本，登记为 1，每次修订成功加一
 * @param sectionId    区段 ID
 * @param startUtc     限速开始时刻（含），UTC
 * @param endUtc       限速结束时刻（不含），UTC，必须晚于 startUtc
 * @param maxSpeedKmh  最高速度，单位 km/h，取值 10～300
 * @param status       限速令状态
 * @param operator     最近一次操作的操作者标识
 * @param createdAt    登记时刻，UTC 毫秒
 * @param updatedAt    最近变更时刻，UTC 毫秒
 * @param revokedAt    撤销时刻，UTC 毫秒；未撤销为 null
 */
public record SpeedRestriction(long id, String restrictionKey, int version, String sectionId,
                               Instant startUtc, Instant endUtc, int maxSpeedKmh,
                               RestrictionStatus status, String operator,
                               long createdAt, long updatedAt, Long revokedAt) {
}