package com.example.starter.consent.dto;

import com.example.starter.consent.Purpose;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 清除请求：撤回后的合规清除操作，标记指定 epoch 的记录已清除。
 *
 * <p>清除只影响对象可见性（标记清除时间），不删除、不改写授权代次与已固化账本。
 *
 * @param requestId  幂等请求标识
 * @param subjectKey 主体标识（合成字符串）
 * @param purpose    用途：RESEARCH 研究 / PERSONALIZATION 个性化
 * @param epoch      待清除的授权代次，必须已经撤回，否则拒绝清除
 */
public record ClearRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 128) String subjectKey,
        @NotNull Purpose purpose,
        @NotNull @Min(1) Integer epoch) {
}
