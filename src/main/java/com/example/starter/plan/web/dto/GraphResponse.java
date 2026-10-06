package com.example.starter.plan.web.dto;

import java.util.List;

/**
 * 线路图响应：当前版本与区段清单。
 */
public record GraphResponse(int version, List<LineSegmentRequest> segments) {
}
