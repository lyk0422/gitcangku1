package com.example.starter.common;

import org.springframework.http.HttpStatus;

/**
 * 业务异常：携带 HTTP 状态码，由全局异常处理器转换为响应体。
 */
public class BizException extends RuntimeException {

    private final HttpStatus status;

    public BizException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }

    /** 422：业务规则不满足（跨层位混包、超量、未采集入包、封签破损后交接等）。 */
    public static BizException unprocessable(String message) {
        return new BizException(HttpStatus.UNPROCESSABLE_ENTITY, message);
    }

    /** 409：资源或请求冲突（重复编号、幂等异参、并发争夺样本等）。 */
    public static BizException conflict(String message) {
        return new BizException(HttpStatus.CONFLICT, message);
    }
}