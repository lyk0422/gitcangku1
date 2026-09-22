package com.example.starter.race.service;

/**
 * 分段业务约束不满足（耗时未严格递增、超过原始完赛耗时、重复分段等），映射 HTTP 422；
 * 与版本冲突（409）区分：请求本身语法合法但违反分段数据不变量，且不写入任何数据。
 */
public class UnprocessableEntityException extends RuntimeException {

    public UnprocessableEntityException(String message) {
        super(message);
    }
}
