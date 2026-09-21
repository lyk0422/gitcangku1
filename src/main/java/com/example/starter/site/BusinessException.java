package com.example.starter.site;

/**
 * 业务异常：携带可区分的 HTTP 状态（400 参数非法 / 404 不存在 / 409 状态冲突 / 422 前置条件不满足）
 * 与稳定错误码，由全局异常处理器转换为统一错误响应。
 */
public class BusinessException extends RuntimeException {

    private final int status;
    private final String code;

    public BusinessException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    public static BusinessException badRequest(String code, String message) {
        return new BusinessException(400, code, message);
    }

    public static BusinessException notFound(String code, String message) {
        return new BusinessException(404, code, message);
    }

    public static BusinessException conflict(String code, String message) {
        return new BusinessException(409, code, message);
    }

    public static BusinessException unprocessable(String code, String message) {
        return new BusinessException(422, code, message);
    }
}
