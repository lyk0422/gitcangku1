package com.example.starter.plan.web.dto;

/**
 * 计划乘务指派视图。
 *
 * @param role     乘务角色：DRIVER 司机 / CONDUCTOR 车长
 * @param crewId   乘务员标识
 * @param qualCode 指派所依据的资质代码
 */
public record PlanCrewView(String role, String crewId, String qualCode) {
}
