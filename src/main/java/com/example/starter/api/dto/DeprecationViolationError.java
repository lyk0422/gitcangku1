package com.example.starter.api.dto;

import java.util.List;

/**
 * 已生效弃用导致锁定失败（422）的错误响应体，violations 按名称、版本稳定排序。
 */
public record DeprecationViolationError(
        String error,
        String message,
        List<DeprecationViolationView> violations) {
}
