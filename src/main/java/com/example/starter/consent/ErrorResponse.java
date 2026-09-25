package com.example.starter.consent;

import java.util.List;

import com.example.starter.consent.dto.ViolationDetail;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 统一错误响应体：code 为稳定业务码，message 为可读描述；
 * violations 仅在批次查询被门禁整体拒绝时携带逐主体阻断明细，其余情况为空且不序列化。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(String code, String message, List<ViolationDetail> violations) {

    public ErrorResponse(String code, String message) {
        this(code, message, null);
    }
}
