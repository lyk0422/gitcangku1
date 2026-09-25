package com.example.starter.plan.web.dto;

/**
 * 站台视图：代码、当前有效长度（米）与版本。
 */
public record PlatformView(String platformCode, int effectiveLength, int version) {
}
