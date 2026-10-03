package com.example.starter.service;

import org.springframework.http.HttpStatus;

/**
 * 业务异常：携带 HTTP 状态、错误码、可读信息及时间线审计所需的上下文。
 * 409 用于状态/幂等冲突，422 用于参数语义错误（时间倒退、区间交叉、站点不匹配等）。
 */
public class BusinessException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final String tagCode;
    private final String animalCode;
    private final String operation;

    public BusinessException(HttpStatus status, String code, String message,
            String tagCode, String animalCode, String operation) {
        super(message);
        this.status = status;
        this.code = code;
        this.tagCode = tagCode;
        this.animalCode = animalCode;
        this.operation = operation;
    }

    public static BusinessException conflict(String code, String message, String tagCode, String operation) {
        return new BusinessException(HttpStatus.CONFLICT, code, message, tagCode, null, operation);
    }

    public static BusinessException unprocessable(String code, String message, String tagCode, String operation) {
        return new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, code, message, tagCode, null, operation);
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    public String getTagCode() {
        return tagCode;
    }

    public String getAnimalCode() {
        return animalCode;
    }

    public String getOperation() {
        return operation;
    }

    public BusinessException withAnimal(String animalCode) {
        return new BusinessException(status, code, getMessage(), tagCode, animalCode, operation);
    }
}