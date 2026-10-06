package com.example.starter.plan.web.dto;

/**
 * 区段绑定响应，含目标变电所与绑定版本。
 */
public record SectionBindingResponse(String sectionId, String substationKey, int version) {
}
