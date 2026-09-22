package com.example.starter.service;

import org.springframework.http.HttpStatus;

/**
 * 公告或预占不存在。
 */
public class NotFoundException extends ApiException {

    public NotFoundException(String message) {
        super(HttpStatus.NOT_FOUND, "NOT_FOUND", message);
    }
}
