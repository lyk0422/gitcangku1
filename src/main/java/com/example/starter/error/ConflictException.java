package com.example.starter.error;

/** 409：当前状态与请求冲突，或幂等键同键异参。 */
public class ConflictException extends ApiException {
    public ConflictException(String message) {
        super(409, message);
    }
}
