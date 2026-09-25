package com.example.starter.race.service;

/**
 * 请求语义合法但当前资源状态无法处理（HTTP 422）；
 * 例如纪录认定时计时未严格优于赛道当前纪录。
 */
public class UnprocessableEntityException extends RuntimeException {

    public UnprocessableEntityException(String message) {
        super(message);
    }
}
