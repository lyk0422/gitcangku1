package com.example.starter.plan.model;

import java.time.Instant;

/**
 * 乘务员资质主记录。资质本身不区分角色，角色由计划发布/改签时的指派决定。
 *
 * @param id                主键
 * @param crewId            乘务员 id
 * @param qualificationCode 资质代码，同一乘务员内唯一
 * @param expiresAtUtc      到期时刻（UTC），须严格晚于计划终到时刻方为有效
 * @param terminated        是否已提前终止
 * @param version           资质版本，每次修改加一，修改/终止须携带 expectedVersion
 */
public record Qualification(long id, String crewId, String qualificationCode,
                            Instant expiresAtUtc, boolean terminated, int version) {
}
