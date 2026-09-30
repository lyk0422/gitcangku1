package com.example.starter.plan.model;

import java.time.Instant;

/**
 * 乘务员在某条线路上的一个资格版本（不可变行；换发新版本、撤销均以状态/新版本体现，旧行保留）。
 *
 * @param id           主键
 * @param memberId     乘务员 id，关联 rail_crew_member.id
 * @param lineId       线路 ID
 * @param gradeLevel   可值乘的最高线路等级，数字越大等级越高，须不小于区段要求等级
 * @param validFromUtc 资格生效时刻（含），UTC
 * @param validToUtc   资格失效时刻（不含），UTC；null 表示长期有效
 * @param version      同一人员同一线路内的资格版本号，从 1 递增
 * @param status       资格状态
 * @param createdAt    版本创建时刻，UTC 毫秒
 * @param revokedAt    撤销时刻，UTC 毫秒；未撤销为 null
 */
public record CrewQualification(long id, long memberId, String lineId, int gradeLevel,
                                Instant validFromUtc, Instant validToUtc, int version,
                                QualificationStatus status, long createdAt, Long revokedAt) {
}
