package com.example.starter.playout.api;

import com.example.starter.playout.api.Dtos.SpliceBlock;
import org.springframework.http.HttpStatus;

import java.util.List;

/**
 * 业务异常，携带明确的 HTTP 状态码与错误码，由全局异常处理器转换为统一错误响应。
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final List<SpliceBlock> details;

    public ApiException(HttpStatus status, String code, String message) {
        this(status, code, message, null);
    }

    public ApiException(HttpStatus status, String code, String message, List<SpliceBlock> details) {
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

    /** 阻断明细（仅 SPLICE_BLOCKED 类错误非空）。 */
    public List<SpliceBlock> details() {
        return details;
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

    /** 422：整次发布被区域插播阻断，携带按区域与条目稳定排序的阻断明细。 */
    public static ApiException spliceBlocked(String message, List<SpliceBlock> details) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "SPLICE_BLOCKED", message, details);
    }
}
