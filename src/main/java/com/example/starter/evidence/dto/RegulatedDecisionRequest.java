package com.example.starter.evidence.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 受监管交接接收/取消请求。接收仅指定收件人，取消仅原保管人。
 *
 * @param requestId       幂等请求键
 * @param expectedVersion 期望的交接版本号，须与当前版本一致
 */
public record RegulatedDecisionRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotNull Long expectedVersion) {
}