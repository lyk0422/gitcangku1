package com.example.starter.api.dto;

import java.util.List;

/**
 * 422 许可证策略违规响应体：除标准错误码外携带稳定排序的违规诊断明细。
 */
public record PolicyViolationError(String error, String message,
                                   List<LicenseViolationResponse> violations) {
}
