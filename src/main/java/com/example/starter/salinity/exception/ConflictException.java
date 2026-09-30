package com.example.starter.salinity.exception;

/**
 * 状态冲突异常，映射 HTTP 409：采样编号重复但内容不同、封存后替换采纳版本、重复封存等。
 */
public class ConflictException extends RuntimeException {

    /**
     * @param message 面向调用方的错误说明
     */
    public ConflictException(String message) {
        super(message);
    }
}
