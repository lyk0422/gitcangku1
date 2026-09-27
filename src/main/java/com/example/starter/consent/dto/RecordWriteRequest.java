package com.example.starter.consent.dto;

import com.example.starter.consent.Purpose;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 记录写入请求：仅当前有效代次可写入，且必须携带与 epoch 相同的驻留区域路由。
 *
 * <p>region 可空仅为兼容驻留能力上线前的调用方：缺省时按当前 epoch 本区域路由（仍是同区域写入）；
 * 显式传入与 epoch 不一致的区域时整次 403。
 *
 * @param requestId  幂等请求标识
 * @param subjectKey 主体标识（合成字符串）
 * @param purpose    用途：RESEARCH 研究 / PERSONALIZATION 个性化
 * @param recordKey  记录键，同一代内唯一
 * @param payload    记录内容（合成字符串）
 * @param region     数据写入的驻留区域代码，必须与当前有效 epoch 的区域一致，否则 403
 */
public record RecordWriteRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 128) String subjectKey,
        @NotNull Purpose purpose,
        @NotBlank @Size(max = 128) String recordKey,
        @NotBlank @Size(max = 8192) String payload,
        @Size(max = 32) @Pattern(regexp = "[A-Z][A-Z0-9_]*",
                message = "区域代码必须为大写字母开头的大写字母/数字/下划线组合") String region) {
}
