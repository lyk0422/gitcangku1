package com.example.starter.plan.web.dto;

/**
 * 区段牵引档案响应。
 */
public record SectionProfileResponse(String sectionId, boolean electrified, boolean dieselAllowed,
                                     int version) {
}