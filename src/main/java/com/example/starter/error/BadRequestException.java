package com.example.starter.error;

/** 400：请求参数缺失或不合法。 */
public class BadRequestException extends ApiException {
    public BadRequestException(String message) {
        super(400, message);
    }
}
