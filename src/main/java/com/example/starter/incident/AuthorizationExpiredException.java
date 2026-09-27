package com.example.starter.incident;

import org.springframework.http.HttpStatus;

/**
 * 资源授权已到期异常（422）。
 * 专用事务语义：抛出前同事务已固化任务 AUTHORIZATION_EXPIRED、租约 EXPIRED 及
 * 到期/拦截证据事件，调用方以 dontRollbackOn 提交这些证据后再向调用方返回错误，
 * 因此失败响应可区分原因、证据保留，且 commandKey 占位尚未写入（失败不占键）。
 * details 固化实际到期时刻、判定时刻与差额，调用方无需猜测。
 */
public class AuthorizationExpiredException extends ApiException {

    public AuthorizationExpiredException(String message, Object details) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, "AUTHORIZATION_EXPIRED", message, details);
    }
}
