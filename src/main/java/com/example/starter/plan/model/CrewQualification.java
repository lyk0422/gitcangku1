package com.example.starter.plan.model;

import java.time.Instant;

/**
 * 乘务员线路资格，区间左闭右开 [validFromUtc, validToUtc)。
 *
 * @param id          主键
 * @param memberCode  乘务员业务键
 * @param version     资格版本，同一乘务员每次授予递增
 * @param lineGrade   资格线路等级，须不低于区段要求等级
 * @param validFromUtc 资格生效时刻（含），UTC
 * @param validToUtc  资格失效时刻（不含），UTC，必须晚于 validFromUtc
 * @param status      资格状态
 */
public record CrewQualification(long id, String memberCode, int version, int lineGrade,
                                Instant validFromUtc, Instant validToUtc,
                                QualificationStatus status) {
}