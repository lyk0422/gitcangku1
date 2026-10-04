package com.example.starter.observation;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 设备显式关闭会话请求：仅当当前水位等于声明末序号时成功。
 *
 * @param requestId 全局唯一请求标识（幂等去重键）
 * @param operator  操作者标识
 * @param finalSeq  设备声明的会话末序号（期望水位），正整数
 */
public record CloseSessionRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 128) String operator,
        @NotNull @Min(1) Integer finalSeq) {
}