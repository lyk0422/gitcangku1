package com.example.starter.api.dto;

import java.util.List;

/**
 * 许可证策略违规错误响应体（HTTP 422），violations 按（名称, 版本）稳定升序。
 */
public record ApiViolationError(
        String error,
        String message,
        List<LicenseViolation> violations) {
}
