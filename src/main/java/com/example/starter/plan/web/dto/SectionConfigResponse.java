package com.example.starter.plan.web.dto;

/**
 * 单线区段换向配置响应。version 从 1 起，每次更新加一。
 */
public record SectionConfigResponse(String sectionId, String directionForward,
                                    String directionBackward, int clearanceSeconds, int version) {
}
