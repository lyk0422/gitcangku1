package com.example.starter.observation.service;

import com.example.starter.observation.dto.ObservationResponse;
import org.springframework.http.HttpStatus;

/**
 * 写操作执行结果：HTTP 状态码与观测响应体；幂等重放时原样返回。
 */
public record WriteResult(HttpStatus status, ObservationResponse body) {
}
