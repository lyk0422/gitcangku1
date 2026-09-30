package com.example.starter.archival.web;

/**
 * 目标资源不存在，映射 HTTP 404。
 */
public class ApiNotFoundException extends RuntimeException {

    public ApiNotFoundException(String message) {
        super(message);
    }
}
