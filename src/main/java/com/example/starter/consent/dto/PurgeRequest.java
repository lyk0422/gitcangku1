package com.example.starter.consent.dto;

import com.example.starter.consent.Purpose;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 清除请求：撤回后对指定 epoch 执行物理清除；仍被生效冻结保留的数据跳过。
 *
 * @param requestId  幂等请求标识
 * @param subjectKey 主体标识
 * @param purpose    用途
 * @param epoch      待清除的授权代次
 */
public record PurgeRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 128) String subjectKey,
        @NotNull Purpose purpose,
        @NotNull @Min(1) Integer epoch) {
}
