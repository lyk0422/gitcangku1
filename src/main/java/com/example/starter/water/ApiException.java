package com.example.starter.water;

import org.springframework.http.HttpStatus;

import java.math.BigDecimal;

/**
 * 业务异常：携带 HTTP 状态与可区分的错误码。
 * 400 参数非法、404 不存在、409 操作人/状态/幂等冲突、422 业务量不满足（配额不足、负降雨/负系数、
 * 窗口不匹配、重复有效降雨）。
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    /** 实际值（如当前持有额度、当前有效降雨量），无数量语义时为 null。 */
    private final String actualValue;
    /** 要求值（如本次所需配额扣减），无数量语义时为 null。 */
    private final String requiredValue;
    /** 差额（要求值减实际值），无数量语义时为 null。 */
    private final String difference;

    public ApiException(HttpStatus status, String code, String message) {
        this(status, code, message, null, null, null);
    }

    public ApiException(HttpStatus status, String code, String message,
                        String actualValue, String requiredValue, String difference) {
        super(message);
        this.status = status;
        this.code = code;
        this.actualValue = actualValue;
        this.requiredValue = requiredValue;
        this.difference = difference;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public String actualValue() {
        return actualValue;
    }

    public String requiredValue() {
        return requiredValue;
    }

    public String difference() {
        return difference;
    }

    public static ApiException badRequest(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }

    public static ApiException notFound(String code, String message) {
        return new ApiException(HttpStatus.NOT_FOUND, code, message);
    }

    public static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }

    public static ApiException quotaExceeded(String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "QUOTA_EXCEEDED", message);
    }

    /** 422：业务规则不满足但无数量差额语义（窗口不匹配、重复有效降雨等）。 */
    public static ApiException unprocessable(String code, String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
    }

    /** 422：业务量不满足，携带实际值、要求值与差额（均为十进制字符串，单位随具体操作）。 */
    public static ApiException unprocessable(String code, String message,
                                             BigDecimal actual, BigDecimal required) {
        BigDecimal safeActual = actual == null ? BigDecimal.ZERO : actual;
        BigDecimal diff = required.subtract(safeActual);
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, code, message,
                WaterService.fmt(safeActual), WaterService.fmt(required), WaterService.fmt(diff));
    }
}
