package com.example.starter.web.error;

import org.springframework.http.HttpStatus;

/**
 * 404：邀约等资源不存在。
 */
public class NotFoundException extends ApiException {

    public NotFoundException(String message) {
        super(HttpStatus.NOT_FOUND, "NOT_FOUND", message);
    }
}
