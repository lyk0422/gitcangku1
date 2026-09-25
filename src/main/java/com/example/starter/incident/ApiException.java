package com.example.starter.incident;

import org.springframework.http.HttpStatus;

/**
 * 业务异常：携带可区分的 HTTP 状态码与错误码。
 * 400 参数非法，404 资源不存在，409 权限/状态/幂等冲突，422 非法状态流转。
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final transient Object details;

    private ApiException(HttpStatus status, String code, String message, Object details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    /** 结构化错误明细（如未完成任务的 groupCode/taskKey、未解除阻塞的事件键列表），无则 null。 */
    public Object details() {
        return details;
    }

    /** 400：请求参数缺失或非法。 */
    public static ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "BAD_REQUEST", message, null);
    }

    /** 404：事件不存在。 */
    public static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", message, null);
    }

    /** 409：权限冲突、状态冲突或幂等键冲突。 */
    public static ApiException conflict(String message) {
        return new ApiException(HttpStatus.CONFLICT, "CONFLICT", message, null);
    }

    /** 409：携带结构化明细的冲突（如解决门禁未完成项、任务未解除阻塞事件列表）。 */
    public static ApiException conflict(String message, Object details) {
        return new ApiException(HttpStatus.CONFLICT, "CONFLICT", message, details);
    }

    /** 422：非法状态流转。 */
    public static ApiException illegalTransition(String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "ILLEGAL_TRANSITION", message, null);
    }

    /** 422：资质校验失败（缺失或有效期未严格覆盖），details 为 CredentialIssueView 列表。 */
    public static ApiException credentialViolation(String message, Object details) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "CREDENTIAL_VIOLATION",
                message, details);
    }
}
