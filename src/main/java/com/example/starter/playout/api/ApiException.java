package com.example.starter.playout.api;

import com.example.starter.playout.api.Dtos.BlockingReasonResponse;
import org.springframework.http.HttpStatus;

import java.util.List;

/**
 * 业务异常，携带明确的 HTTP 状态码与错误码，由全局异常处理器转换为统一错误响应。
 * 发布被字幕/黑屏阻断时可携带稳定排序的阻断原因列表。
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final List<BlockingReasonResponse> blocking;

    public ApiException(HttpStatus status, String code, String message) {
        this(status, code, message, List.of());
    }

    public ApiException(HttpStatus status, String code, String message,
                        List<BlockingReasonResponse> blocking) {
        super(message);
        this.status = status;
        this.code = code;
        this.blocking = List.copyOf(blocking);
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public List<BlockingReasonResponse> blocking() {
        return blocking;
    }

    /** 400：请求参数不合法。 */
    public static ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "BAD_REQUEST", message);
    }

    /** 404：引用的资源不存在。 */
    public static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", message);
    }

    /** 409：版本、幂等或状态冲突。 */
    public static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }

    /** 422：业务规则不满足。 */
    public static ApiException unprocessable(String code, String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
    }

    /** 422：整次发布被阻断，稳定列出全部阻断区域与窗口。 */
    public static ApiException publishBlocked(List<BlockingReasonResponse> blocking) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "PUBLISH_BLOCKED",
                "发布被未审核字幕或黑屏窗口阻断", blocking);
    }
}
