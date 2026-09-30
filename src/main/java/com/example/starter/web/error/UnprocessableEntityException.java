package com.example.starter.web.error;

import org.springframework.http.HttpStatus;

/**
 * 422：请求语义可解析但业务上无法处理（读数缺失、负电量、时段不一致、超承诺上限等）。
 */
public class UnprocessableEntityException extends ApiException {

    public UnprocessableEntityException(String message) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, "UNPROCESSABLE_ENTITY", message);
    }
}
