package com.example.starter.plan.model;

import java.time.Instant;

/**
 * 乘务员线路资格当前状态（每人一行，版本化）。
 *
 * @param id           主键
 * @param crewMemberId 乘务员 ID，全表唯一
 * @param lineGrade    资格线路等级，须不低于计划经过区段的最高等级
 * @param validFromUtc 资格生效时刻（含），UTC
 * @param validToUtc   资格失效时刻（不含），UTC，必须晚于 validFromUtc
 * @param version      资格版本，授予为 1，撤销与再授予各加一
 * @param status       资格状态：ACTIVE 有效 / REVOKED 已撤销
 */
public record CrewQualification(long id, String crewMemberId, int lineGrade,
                                Instant validFromUtc, Instant validToUtc,
                                int version, String status) {

    /** 资格状态：有效。 */
    public static final String STATUS_ACTIVE = "ACTIVE";

    /** 资格状态：已撤销。 */
    public static final String STATUS_REVOKED = "REVOKED";
}