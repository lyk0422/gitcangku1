package com.example.starter.film.exception;

/**
 * 422：请求语义不完整或格式错误（缺段、摘要格式错误、序号越界等），不进行任何写入。
 */
public class UnprocessableException extends RuntimeException {

    public UnprocessableException(String message) {
        super(message);
    }
}
