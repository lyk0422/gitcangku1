package com.example.starter.plan.model;

import java.time.Instant;

/**
 * 铁路气象限速令：按区段与 UTC 左闭右开时段登记的最高速度限制。
 * 同一区段同一时刻可叠加多条，裁决时以最低速度为准。
 *
 * @param id           主键，同时作为限速令标识
 * @param sectionId    区段 ID
 * @param startUtc     限速开始时刻（含），UTC
 * @param endUtc       限速结束时刻（不含），UTC，必须晚于 startUtc
 * @param maxSpeedKmh  最高速度，单位 km/h，范围 10～300
 * @param status       限速令状态
 * @param version      限速令版本，登记时恒为 1；修订通过撤销原令并登记新令完成
 * @param operator     操作者标识，可为 null（未提供）
 * @param createdAt    登记时刻，UTC
 * @param revokedAt    撤销时刻，UTC；null 表示未撤销
 */
public record SpeedRestriction(long id, String sectionId, Instant startUtc, Instant endUtc,
                               int maxSpeedKmh, RestrictionStatus status, int version,
                               String operator, Instant createdAt, Instant revokedAt) {
}
