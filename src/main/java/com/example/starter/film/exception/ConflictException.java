package com.example.starter.film.exception;

/**
 * 409：状态或乐观版本冲突（基于旧版次提交、审核过期、幂等键异参、撤销后再交付等）。
 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
