package com.example.starter.firmware.error;

import org.springframework.http.HttpStatus;

/**
 * 业务异常，携带 HTTP 状态码与稳定错误码；空间档案冲突可附带当前设备空间档案版本。
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final Integer expectedDeviceVersion;

    public ApiException(HttpStatus status, String code, String message) {
        this(status, code, message, null);
    }

    public ApiException(HttpStatus status, String code, String message, Integer expectedDeviceVersion) {
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

    /**
     * 设备空间档案冲突时返回的当前档案版本，供调用方校正 expectedDeviceVersion 后重试；无则为 null。
     */
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
     * 设备空间档案冲突（版本不一致或容量低于当前镜像加活跃预留），响应携带当前档案版本。
     */
    public static ApiException deviceVersionConflict(String code, String message, int currentVersion) {
        return new ApiException(HttpStatus.CONFLICT, code, message, currentVersion);
    }

    /**
     * 暂存空间不足：不生成任务或预留。
     */
    public static ApiException unprocessable(String code, String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
    }
}
