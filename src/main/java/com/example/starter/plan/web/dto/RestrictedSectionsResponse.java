package com.example.starter.plan.web.dto;

import java.util.List;

/**
 * 全部限制区段配置响应：当前限制区段配置版本与区段清单（按区段 ID 字典序）。
 */
public record RestrictedSectionsResponse(int sectionVersion,
                                         List<RestrictedSectionItem> sections) {

    /**
     * 单个限制区段配置项。
     */
    public record RestrictedSectionItem(String sectionId, List<String> allowedClasses) {
    }
}
