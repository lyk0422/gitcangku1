package com.example.starter.error;

/** 冲突（409）：幂等键异参重放、电芯被其他进行中批次占用等。 */
public class ConflictException extends ApiException {

    public ConflictException(String message) {
        super(409, message);
    }
}