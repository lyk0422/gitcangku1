package com.example.starter.firmware.error;

import org.springframework.http.HttpStatus;

/**
 * 证书域拒绝异常：在业务异常基础上携带操作与上下文（固件版本、链版本、设备），
 * 用于在事务回滚后落拒绝原因审计日志。HTTP 语义与 ApiException 一致。
 */
public class CertRejectionException extends ApiException {

    private final String operation;
    private final String firmwareVersion;
    private final String chainVersion;
    private final String deviceId;

    public CertRejectionException(HttpStatus status, String code, String message, String operation,
                                  String firmwareVersion, String chainVersion, String deviceId) {
        super(status, code, message);
        this.operation = operation;
        this.firmwareVersion = firmwareVersion;
        this.chainVersion = chainVersion;
        this.deviceId = deviceId;
    }

    public String operation() {
        return operation;
    }

    public String firmwareVersion() {
        return firmwareVersion;
    }

    public String chainVersion() {
        return chainVersion;
    }

    public String deviceId() {
        return deviceId;
    }

    public static CertRejectionException unprocessable(String code, String message, String operation,
                                                       String firmwareVersion, String chainVersion,
                                                       String deviceId) {
        return new CertRejectionException(HttpStatus.UNPROCESSABLE_ENTITY, code, message, operation,
                firmwareVersion, chainVersion, deviceId);
    }

    public static CertRejectionException conflict(String code, String message, String operation,
                                                  String firmwareVersion, String chainVersion,
                                                  String deviceId) {
        return new CertRejectionException(HttpStatus.CONFLICT, code, message, operation,
                firmwareVersion, chainVersion, deviceId);
    }

    public static CertRejectionException notFound(String code, String message, String operation,
                                                  String firmwareVersion, String chainVersion,
                                                  String deviceId) {
        return new CertRejectionException(HttpStatus.NOT_FOUND, code, message, operation,
                firmwareVersion, chainVersion, deviceId);
    }
}
