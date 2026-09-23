package com.example.starter.calibration.api;

import org.springframework.http.HttpStatus;

/**
 * 业务异常：携带可区分的 HTTP 状态码与业务错误码。
 * 400 参数非法、404 不存在、409 状态冲突、422 缺少有效证书。
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    /** 400：参数非法。 */
    public static ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "BAD_REQUEST", message);
    }

    /** 404：资源不存在。 */
    public static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", message);
    }

    /** 409：状态冲突（重复放行、重复撤销、区间重叠、幂等键冲突等）。 */
    public static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }

    /** 422：测量时刻无匹配的有效证书。 */
    public static ApiException unprocessable(String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "NO_MATCHING_CERTIFICATE", message);
    }

    /** 422：业务规则不满足（指定业务错误码，如无有效标准器版本、父级标准器不存在）。 */
    public static ApiException unprocessable(String code, String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
    }
}
