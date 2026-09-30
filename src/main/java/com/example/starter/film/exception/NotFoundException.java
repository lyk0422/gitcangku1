package com.example.starter.film.exception;

/**
 * 404：影片、审核、母版或回执不存在。
 */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
