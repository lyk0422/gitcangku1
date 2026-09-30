package com.example.starter.plan.model;

import java.time.Instant;

/**
 * 计划乘务组快照的逐人记录：发布时的人员名单及其所依据的资格版本（冗余区间/等级）。
 * 资格事后换发或撤销均不改写本快照；冲突检测只认状态为 PUBLISHED 的计划。
 *
 * @param id                   主键
 * @param planId               所属计划 id，关联 rail_day_plan.id
 * @param seq                  组内序号，从 0 开始，按人员业务键升序稳定排列
 * @param memberId             乘务员 id
 * @param memberKey            乘务员工号快照
 * @param memberName           乘务员姓名快照
 * @param qualificationId      发布依据的资格版本 id，关联 rail_crew_qualification.id
 * @param qualificationVersion 发布依据的资格版本号
 * @param gradeLevel           资格可值乘最高等级快照
 * @param validFromUtc         资格生效时刻（含）快照，UTC
 * @param validToUtc           资格失效时刻（不含）快照，UTC，null 表示长期有效
 */
public record PlanCrewMember(long id, long planId, int seq, long memberId, String memberKey,
                             String memberName, long qualificationId, int qualificationVersion,
                             int gradeLevel, Instant validFromUtc, Instant validToUtc) {
}
