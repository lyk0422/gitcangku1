package com.example.starter.plan.web.dto;

import java.util.List;
import java.util.Map;

/**
 * 统一错误响应：code 区分错误类别，details 携带结构化明细（如冲突区段与计划）。
 */
public record ErrorResponse(String code, String message, List<Map<String, Object>> details) {
}
