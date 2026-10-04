package com.example.starter.api;

import org.springframework.http.HttpStatus;

import java.util.List;

/**
 * 业务错误，携带返回给客户端的 HTTP 状态码与错误码。
 */
public class ApiException extends RuntimeException {

    /** 错误码，例如 ZONE_NOT_FOUND / VERSION_CONFLICT / IDEMPOTENT_PARAM_MISMATCH。 */
    private final String code;
    /** 映射的 HTTP 状态。 */
    private final HttpStatus status;
    /** 间隔冲突的许可 ID 列表（仅 PERMIT_SEPARATION_CONFLICT 使用），否则为 null。 */
    private final List<String> conflictPermitIds;

    public ApiException(HttpStatus status, String code, String message) {
        this(status, code, message, null);
    }

    public ApiException(HttpStatus status, String code, String message,
                        List<String> conflictPermitIds) {
        super(message);
        this.status = status;
        this.code = code;
        this.conflictPermitIds = conflictPermitIds;
    }

    public String code() {
        return code;
    }

    public HttpStatus status() {
        return status;
    }

    /** 冲突许可 ID 列表；无则返回 null。 */
    public List<String> conflictPermitIds() {
        return conflictPermitIds;
    }
}