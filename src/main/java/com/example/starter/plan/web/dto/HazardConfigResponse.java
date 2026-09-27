package com.example.starter.plan.web.dto;

import java.util.List;

/**
 * 危险品配置查询响应：隔离规则与限制区段配置的当前完整状态，稳定排序。
 */
public record HazardConfigResponse(List<SeparationRuleView> separationRules,
                                   List<SectionRestrictionView> sectionRestrictions) {
}
