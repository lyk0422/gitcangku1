package com.example.starter.support;

/**
 * 业务 API 异常，携带 HTTP 状态码与错误码。
 *
 * @param details 可选的结构化失败明细（如阻断弃用列表、实际值/要求值/差额），
 *                序列化为错误响应的 details 字段；无明细时为 null
 */
public class ApiException extends RuntimeException {

    private final int status;
    private final String code;
    private final transient Object details;

    public ApiException(int status, String code, String message) {
        this(status, code, message, null);
    }

    public ApiException(int status, String code, String message, Object details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details;
    }

    public int getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    public Object getDetails() {
        return details;
    }

    public static ApiException badRequest(String message) {
        return new ApiException(400, "BAD_REQUEST", message);
    }

    public static ApiException notFound(String message) {
        return new ApiException(404, "NOT_FOUND", message);
    }

    public static ApiException conflict(String message) {
        return new ApiException(409, "CONFLICT", message);
    }

    public static ApiException unprocessable(String message) {
        return new ApiException(422, "UNPROCESSABLE_ENTITY", message);
    }

    /** 422 且携带可区分原因的错误码。 */
    public static ApiException unprocessable(String code, String message) {
        return new ApiException(422, code, message);
    }

    /** 422 且携带可区分原因的错误码与结构化明细。 */
    public static ApiException unprocessable(String code, String message, Object details) {
        return new ApiException(422, code, message, details);
    }
}
