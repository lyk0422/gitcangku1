package com.example.starter.plan.web.dto;

import java.util.List;

/**
 * 限制区段配置视图：开放清单按类别字典序返回，区段间按 sectionId 字典序稳定排序。
 */
public record SectionRestrictionView(String sectionId, boolean restricted, int version,
                                     List<String> allowedCategories) {
}
