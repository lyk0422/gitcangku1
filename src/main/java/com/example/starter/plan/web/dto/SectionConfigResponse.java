package com.example.starter.plan.web.dto;

/**
 * 单线区段换向配置响应。
 *
 * @param sectionId        单线区段 ID
 * @param clearanceSeconds 换向清空间隔，整秒，范围 1 至 600
 * @param version          配置版本，从 1 起
 */
public record SectionConfigResponse(String sectionId, int clearanceSeconds, int version) {
}
