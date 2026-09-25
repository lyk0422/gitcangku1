package com.example.starter.plan.model;

/**
 * 计划乘务指派快照：发布/改签时按角色记录乘务员与所依据资质。
 *
 * @param id       主键
 * @param planId   所属计划 id
 * @param role     乘务角色
 * @param crewId   乘务员标识
 * @param qualCode 指派所依据的资质代码
 */
public record PlanCrew(long id, long planId, CrewRole role, String crewId, String qualCode) {
}
