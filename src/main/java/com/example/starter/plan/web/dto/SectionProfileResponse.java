package com.example.starter.plan.web.dto;

/**
 * 线路区段档案响应。
 */
public record SectionProfileResponse(String sectionId, int designSpeedKmh, int minHeadwayMinutes) {
}
