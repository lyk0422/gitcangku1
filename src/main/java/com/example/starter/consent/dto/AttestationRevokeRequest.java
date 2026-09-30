package com.example.starter.consent.dto;

import com.example.starter.consent.Purpose;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 接收方承诺撤回请求：撤回为终态，不删除历史读取记录，也不恢复旧 epoch 的承诺。
 *
 * @param requestId    幂等请求标识
 * @param recipientKey 接收方标识
 * @param subjectKey   授权主体标识
 * @param purpose      承诺用途
 * @param epoch        承诺对应的授权代次
 */
public record AttestationRevokeRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 128) String recipientKey,
        @NotBlank @Size(max = 128) String subjectKey,
        @NotNull Purpose purpose,
        @NotNull @Min(1) Integer epoch) {
}
