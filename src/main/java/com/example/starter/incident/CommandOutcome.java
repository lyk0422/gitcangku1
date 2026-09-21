package com.example.starter.incident;

/**
 * 命令执行结果：HTTP 状态码 + 响应体，用于幂等重放时还原首次结果。
 */
public record CommandOutcome<T>(int status, T body) {
}
