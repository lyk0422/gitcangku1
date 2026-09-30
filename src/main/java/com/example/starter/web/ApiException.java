package com.example.starter.web;

/**
 * 业务可处理异常；mapped 为建议返回的 HTTP 状态码（409/422 等）。
 */
public class ApiException extends RuntimeException {

    private final int status;

    public ApiException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int getStatus() {
        return status;
    }

    public static ApiException conflict(String message) {
        return new ApiException(409, message);
    }

    public static ApiException unprocessable(String message) {
        return new ApiException(422, message);
    }

    public static ApiException notFound(String message) {
        return new ApiException(404, message);
    }
}
