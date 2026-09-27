package com.example.starter.maintenance;

import org.springframework.http.HttpStatus;

/**
 * 观测站维护业务异常：携带 HTTP 状态与可区分原因的错误码，由全局异常处理器转为错误响应。
 */
public class MaintenanceApiException extends RuntimeException {

    private final HttpStatus status;
    private final String errorCode;

    private MaintenanceApiException(HttpStatus status, String errorCode, String message) {
        super(message);
        this.status = status;
        this.errorCode = errorCode;
    }

    public HttpStatus status() {
        return status;
    }

    public String errorCode() {
        return errorCode;
    }

    /**
     * 400：请求参数不合法（区间非正时长、批次为空等）。
     */
    public static MaintenanceApiException badRequest(String errorCode, String message) {
        return new MaintenanceApiException(HttpStatus.BAD_REQUEST, errorCode, message);
    }

    /**
     * 404：站点、窗口、观测、冲突簇或快照不存在。
     */
    public static MaintenanceApiException notFound(String errorCode, String message) {
        return new MaintenanceApiException(HttpStatus.NOT_FOUND, errorCode, message);
    }

    /**
     * 409：通用冲突（窗口重叠、早于启用时刻、重复登记、状态不允许、requestId 异参复用等）。
     */
    public static MaintenanceApiException conflict(String errorCode, String message) {
        return new MaintenanceApiException(HttpStatus.CONFLICT, errorCode, message);
    }

    /**
     * 422：业务语义不允许但非冲突（如全部观测受维护影响只能裁决、簇为空无法合并）。
     */
    public static MaintenanceApiException unprocessable(String errorCode, String message) {
        return new MaintenanceApiException(HttpStatus.UNPROCESSABLE_ENTITY, errorCode, message);
    }
}
