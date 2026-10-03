package com.example.starter.plan.model;

import java.time.Instant;

/**
 * 发布时乘务组与资格依据快照（不可变）。计划取消后仍保留，
 * 作为历史发布的资格版本与人员名单。
 *
 * @param id                   主键
 * @param planId               所属计划 id
 * @param seq                  乘务组内序号，保持发布请求顺序
 * @param crewMemberId         乘务员 ID
 * @param qualificationId      发布时依据的资格 id
 * @param qualificationVersion 发布时依据的资格版本
 * @param lineGrade            发布时资格线路等级快照
 * @param validFromUtc         发布时资格生效时刻快照（含），UTC
 * @param validToUtc           发布时资格失效时刻快照（不含），UTC
 * @param tripStartUtc         计划行程开始时刻（含），UTC
 * @param tripEndUtc           计划行程结束时刻（不含），UTC
 */
public record PublishedCrewAssignment(long id, long planId, int seq, String crewMemberId,
                                      long qualificationId, int qualificationVersion, int lineGrade,
                                      Instant validFromUtc, Instant validToUtc,
                                      Instant tripStartUtc, Instant tripEndUtc) {
}