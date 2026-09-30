package com.example.starter.salinity.exception;

/**
 * 业务规则不可处理异常，映射 HTTP 422：越界采样、跨航次质控、封存缺深度等。
 */
public class BusinessRuleException extends RuntimeException {

    /**
     * @param message 面向调用方的错误说明
     */
    public BusinessRuleException(String message) {
        super(message);
    }
}
