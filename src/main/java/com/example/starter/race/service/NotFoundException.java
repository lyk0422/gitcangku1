package com.example.starter.race.service;

/**
 * 请求的资源不存在（赛事、选手或处罚），映射 HTTP 404。
 */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
