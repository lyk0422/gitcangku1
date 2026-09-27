package com.example.starter.plan.model;

/**
 * 限制区段配置（不含开放清单，清单单独按区段查询）。
 *
 * @param sectionId 区段 ID
 * @param restricted 是否限制通行：true 时仅开放清单内类别可经过
 * @param version   该区段限制配置版本，每次变更加一
 */
public record SectionRestriction(String sectionId, boolean restricted, int version) {
}
