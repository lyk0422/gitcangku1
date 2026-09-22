package com.example.starter.error;

/** 401：缺少操作者头或角色无法识别。 */
public class UnauthorizedException extends ApiException {
    public UnauthorizedException(String message) {
        super(401, message);
    }
}
