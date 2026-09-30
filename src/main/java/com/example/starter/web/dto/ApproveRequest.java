package com.example.starter.web.dto;

/**
 * 管理员批准请求体；requestId 用于批准接口幂等。
 */
public record ApproveRequest(String requestId) {
}
