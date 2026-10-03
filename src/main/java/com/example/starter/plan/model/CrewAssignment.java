package com.example.starter.plan.model;

import java.time.Instant;

/**
 * 发布时乘务占用快照（不可变），保留发布当时的资格版本与人员名单。
 *
 * @param id              主键
 * @param planId          所属计划 id
 * @param groupCode       发布时登记的乘务组业务键
 * @param memberCode      乘务员业务键
 * @param qualVersion     发布依据的资格版本
 * @param qualLineGrade   发布时资格线路等级快照
 * @param qualValidFromUtc 发布时资格生效时刻快照（含），UTC
 * @param qualValidToUtc  发布时资格失效时刻快照（不含），UTC
 * @param startUtc        乘务占用开始时刻（含），UTC，等于计划行程最早开始
 * @param endUtc          乘务占用结束时刻（不含），UTC，等于计划行程最晚结束
 */
public record CrewAssignment(long id, long planId, String groupCode, String memberCode,
                             int qualVersion, int qualLineGrade,
                             Instant qualValidFromUtc, Instant qualValidToUtc,
                             Instant startUtc, Instant endUtc) {
}