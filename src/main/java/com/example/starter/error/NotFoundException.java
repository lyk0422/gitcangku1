package com.example.starter.error;

/** 资源不存在（404）。 */
public class NotFoundException extends ApiException {

    public NotFoundException(String message) {
        super(404, message);
    }
}