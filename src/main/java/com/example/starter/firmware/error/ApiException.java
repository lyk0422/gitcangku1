package com.example.starter.firmware.error;

import org.springframework.http.HttpStatus;

/**
 * 业务异常，携带 HTTP 状态码与稳定错误码。
 * expectedDeviceVersion 仅设备空间维护冲突时携带，为当前空间档案版本，其余场景为 null。
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final Integer expectedDeviceVersion;

    public ApiException(HttpStatus status, String code, String message) {
        this(status, code, message, null);
    }

    private ApiException(HttpStatus status, String code, String message, Integer expectedDeviceVersion) {
        super(message);
        this.status = status;
        this.code = code;
        this.expectedDeviceVersion = expectedDeviceVersion;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public Integer expectedDeviceVersion() {
        return expectedDeviceVersion;
    }

    public static ApiException badRequest(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }

    public static ApiException notFound(String code, String message) {
        return new ApiException(HttpStatus.NOT_FOUND, code, message);
    }

    public static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }

    /**
     * 设备空间维护冲突（版本不匹配或容量低于占用），响应携带当前空间档案版本。
     */
    public static ApiException deviceVersionConflict(String code, String message, int currentSpaceVersion) {
        return new ApiException(HttpStatus.CONFLICT, code, message, currentSpaceVersion);
    }

    /**
     * 暂存空间不足：不生成任务或预留。
     */
    public static ApiException unprocessable(String code, String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
    }
}
