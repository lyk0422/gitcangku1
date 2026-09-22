package com.example.starter.error;

import org.springframework.http.HttpStatus;

/**
 * 400 请求非法：坐标越界、矩形退化、点列数量或内容不满足约束等。
 */
public class BadRequestException extends ApiException {

    public BadRequestException(String code, String message) {
        super(HttpStatus.BAD_REQUEST, code, message);
    }
}
