package com.example.starter.coral;

/**
 * 业务冲突异常，携带HTTP状态码；409=资源冲突，422=状态语义不允许。
 */
public class BizException extends RuntimeException {

    private final int status;

    public BizException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() {
        return status;
    }

    public static BizException conflict(String message) {
        return new BizException(409, message);
    }

    public static BizException unprocessable(String message) {
        return new BizException(422, message);
    }

    public static BizException notFound(String message) {
        return new BizException(404, message);
    }
}