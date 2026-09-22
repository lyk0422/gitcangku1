package com.example.starter.error;

/** 404：实验、分配或揭盲申请不存在。 */
public class NotFoundException extends ApiException {
    public NotFoundException(String message) {
        super(404, message);
    }
}
