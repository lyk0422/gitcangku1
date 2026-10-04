package com.example.starter.session;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 设备显式关闭会话请求：仅当水位等于声明末序号时成功。
 *
 * @param requestId 全局唯一请求标识（幂等去重键）
 * @param lastSeq   设备声明的末序号，0..100
 */
public record CloseSessionRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotNull @Min(0) @Max(100) Integer lastSeq) {
}