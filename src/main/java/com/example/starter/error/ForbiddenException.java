package com.example.starter.error;

/** 403：角色或操作者身份无权执行该操作。 */
public class ForbiddenException extends ApiException {
    public ForbiddenException(String message) {
        super(403, message);
    }
}
