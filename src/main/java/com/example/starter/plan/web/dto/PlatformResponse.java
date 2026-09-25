package com.example.starter.plan.web.dto;

/**
 * 站台响应。
 *
 * @param code            站台代码
 * @param effectiveLength 当前有效长度（辆）
 */
public record PlatformResponse(String code, int effectiveLength) {
}
