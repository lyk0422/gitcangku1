package com.example.starter.error;

import org.springframework.http.HttpStatus;

/** 业务异常：携带 HTTP 状态码与稳定错误码，由全局异常处理器转换为响应体。 */
public class BizException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public BizException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    /** 422：业务规则不满足（无入场称量、时间倒退、负净重、逾期争议等）。 */
    public static BizException unprocessable(String code, String message) {
        return new BizException(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
    }

    /** 409：状态冲突（幂等异参重放、重复裁决、未裁决冻结、冻结后改写等）。 */
    public static BizException conflict(String code, String message) {
        return new BizException(HttpStatus.CONFLICT, code, message);
    }

    /** 404：资源不存在。 */
    public static BizException notFound(String code, String message) {
        return new BizException(HttpStatus.NOT_FOUND, code, message);
    }
}