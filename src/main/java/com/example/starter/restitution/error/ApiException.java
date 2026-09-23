package com.example.starter.restitution.error;

/**
 * 业务可预期错误，携带 HTTP 状态码与可选的结构化错误体。
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

    public static ApiException badRequest(String message) {
        return new ApiException(400, "bad_request", message);
    }

    public static ApiException notFound(String message) {
        return new ApiException(404, "not_found", message);
    }

    public static ApiException conflict(String code, String message) {
        return new ApiException(409, code, message);
    }

    public static ApiException forbidden(String message) {
        return new ApiException(403, "forbidden", message);
    }

    public static ApiException unprocessable(String message, Object details) {
        return new ApiException(422, "unprocessable_decision", message, details);
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
}
