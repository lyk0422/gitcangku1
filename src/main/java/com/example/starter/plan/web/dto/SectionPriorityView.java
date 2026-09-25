package com.example.starter.plan.web.dto;

/**
 * 区段走廊等级视图。
 *
 * @param sectionId 区段 ID
 * @param priority  登记等级 1～5；未登记时为 null，抢占判定按最低等级 1
 */
public record SectionPriorityView(String sectionId, Integer priority) {
}
