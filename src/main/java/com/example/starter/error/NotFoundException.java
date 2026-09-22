package com.example.starter.error;

import org.springframework.http.HttpStatus;

/**
 * 404 未找到：航线、禁飞区或审核结果不存在。
 */
public class NotFoundException extends ApiException {

    public NotFoundException(String code, String message) {
        super(HttpStatus.NOT_FOUND, code, message);
    }
}
