package com.example.starter.error;

import org.springframework.http.HttpStatus;

/**
 * 409 冲突：版本不匹配、唯一标识重复、幂等键异参等状态冲突。
 */
public class ConflictException extends ApiException {

    public ConflictException(String code, String message) {
        super(HttpStatus.CONFLICT, code, message);
    }
}
