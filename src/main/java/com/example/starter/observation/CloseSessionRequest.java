package com.example.starter.observation;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 设备显式关闭会话请求：仅当当前水位等于声明末序号时成功。
 *
 * @param requestId       关闭请求标识（幂等去重键）
 * @param deviceId        设备标识
 * @param sessionId       会话标识
 * @param declaredLastSeq 设备声明的会话末序号，须等于当前水位
 * @param operator        操作者标识
 */
public record CloseSessionRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 64) String deviceId,
        @NotBlank @Size(max = 64) String sessionId,
        @NotNull @Min(0) Integer declaredLastSeq,
        @NotBlank @Size(max = 128) String operator) {
}