package com.example.starter.race.service;

/**
 * 业务冲突：版本不匹配、赛事已封榜、唯一键冲突或幂等异参冲突，映射 HTTP 409。
 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
