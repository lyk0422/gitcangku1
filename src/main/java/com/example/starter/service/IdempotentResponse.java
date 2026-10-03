package com.example.starter.service;

/** 幂等执行结果：HTTP 状态码与响应报文。 */
public record IdempotentResponse(int status, String body) {
}