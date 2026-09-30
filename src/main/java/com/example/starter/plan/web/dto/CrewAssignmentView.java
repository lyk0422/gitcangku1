package com.example.starter.plan.web.dto;

import java.time.Instant;

/**
 * 已发布计划乘务快照中的逐人记录：发布时名单与所依据的资格版本冗余信息。
 * 资格事后换发、撤销均不改写本快照。
 *
 * @param validToUtc 资格失效时刻（不含），UTC，null 表示长期有效
 */
public record CrewAssignmentView(String memberKey, String memberName, int qualificationVersion,
                                 int gradeLevel, Instant validFromUtc, Instant validToUtc) {
}
