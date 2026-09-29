package com.example.starter.consent;

import java.util.List;

import com.example.starter.consent.dto.QueryFailureDetail;

/**
 * 统一错误响应体：code 为稳定业务码，message 为可读描述；
 * details 为可选的可定位原因明细（实际值/要求值/差额），无明细时为 null 以保持既有响应形状。
 */
public record ErrorResponse(String code, String message, List<QueryFailureDetail> details) {

    public ErrorResponse(String code, String message) {
        this(code, message, null);
    }
}
