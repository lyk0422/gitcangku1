package com.example.starter.consent.dto;

import com.example.starter.consent.Purpose;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 授权请求：按“主体＋用途”创建或复用当前有效授权代次，并为该 epoch 绑定唯一驻留区域。
 *
 * <p>region 可空仅为兼容驻留能力上线前的调用方：缺省时绑定预置并预先登记的 DEFAULT 区域；
 * 显式传入时必须为已登记区域，且当前有效 epoch 携带不同区域返回 403（同 epoch 区域不可修改）。
 *
 * @param requestId  幂等请求标识，同一 requestId 相同参数重试返回原结果
 * @param subjectKey 主体标识（合成字符串）
 * @param purpose    用途：RESEARCH 研究 / PERSONALIZATION 个性化
 * @param region     驻留区域代码，必须预先登记；同 epoch 的区域不可直接修改，跨区域只能走迁移
 */
public record GrantRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 128) String subjectKey,
        @NotNull Purpose purpose,
        @Size(max = 32) @Pattern(regexp = "[A-Z][A-Z0-9_]*",
                message = "区域代码必须为大写字母开头的大写字母/数字/下划线组合") String region) {
}
