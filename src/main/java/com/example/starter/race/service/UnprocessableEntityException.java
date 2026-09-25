package com.example.starter.race.service;

/**
 * 请求语义不满足领域约束（如接力耗时不满足递增/时刻先后），映射 HTTP 422。
 * 与 {@link BadRequestException}（400，参数格式错误）区分。
 */
public class UnprocessableEntityException extends RuntimeException {

    public UnprocessableEntityException(String message) {
        super(message);
    }
}
