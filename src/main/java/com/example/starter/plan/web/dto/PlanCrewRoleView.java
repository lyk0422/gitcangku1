package com.example.starter.plan.web.dto;

import java.util.List;

/**
 * 计划乘务资质视图：两角色指派、各自资质清单与合格性评估。
 *
 * @param role           角色：DRIVER / CONDUCTOR
 * @param crewId         指派的乘务员 id，未指派时为 null
 * @param qualified      该角色当前是否满足完整资质（未指派为 false）
 * @param qualifications 该乘务员当前全部资质（含已终止），按资质代码升序
 */
public record PlanCrewRoleView(String role, String crewId, boolean qualified,
                               List<QualificationResponse> qualifications) {
}
