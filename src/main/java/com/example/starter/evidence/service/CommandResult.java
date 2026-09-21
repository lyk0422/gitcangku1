package com.example.starter.evidence.service;

/**
 * 命令执行结果：HTTP 状态与响应体（将被序列化存入幂等日志用于重放）。
 */
public record CommandResult(int httpStatus, Object body) {
}
