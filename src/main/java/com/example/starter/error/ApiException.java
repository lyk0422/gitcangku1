package com.example.starter.error;

/**
 * 业务异常：携带 HTTP 状态码与稳定错误码，由全局异常处理器转换为响应体。
 */
public class ApiException extends RuntimeException {

    private final int status;
    private final String code;

    public ApiException(int status, String code, String message) {
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

    /** 404：资源不存在。 */
    public static ApiException notFound(String code, String message) {
        return new ApiException(404, code, message);
    }

    /** 409：幂等键冲突（同 requestId 不同参数）。 */
    public static ApiException conflict(String code, String message) {
        return new ApiException(409, code, message);
    }

    /** 422：业务规则拒绝（检查点缺失、有人进入、确认或指令过期等）。 */
    public static ApiException unprocessable(String code, String message) {
        return new ApiException(422, code, message);
    }
}