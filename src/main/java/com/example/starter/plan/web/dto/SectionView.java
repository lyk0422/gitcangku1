package com.example.starter.plan.web.dto;

/**
 * 区段等级视图。
 *
 * @param sectionId 区段 ID
 * @param priority  走廊等级 1～5，数值越大优先级越高
 */
public record SectionView(String sectionId, Integer priority) {
}
