package com.example.starter.service;

/** 写操作结果：body 为领域对象，replayed 表示同参幂等重放（未产生新写入）。 */
public record OpResult<T>(T body, boolean replayed) {

    public static <T> OpResult<T> created(T body) {
        return new OpResult<>(body, false);
    }

    public static <T> OpResult<T> replayed(T body) {
        return new OpResult<>(body, true);
    }
}