package com.example.starter.consent.dto;

import com.example.starter.consent.Purpose;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 授权请求：按“主体＋用途”创建或复用当前有效授权代次。
 *
 * @param requestId  幂等请求标识，同一 requestId 相同参数重试返回原结果
 * @param subjectKey 主体标识（合成字符串）
 * @param purpose    用途：RESEARCH 研究 / PERSONALIZATION 个性化
 * @param regionCode 新代次登记的数据驻留区域代码（必须预先登记）；NULL 表示不登记区域。
 *                   同一 epoch 的区域登记后不可修改
 */
public record GrantRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 128) String subjectKey,
        @NotNull Purpose purpose,
        @Size(max = 64) String regionCode) {

    /**
     * 兼容构造：不登记驻留区域的授权请求。
     */
    public GrantRequest(String requestId, String subjectKey, Purpose purpose) {
        this(requestId, subjectKey, purpose, null);
    }
}
