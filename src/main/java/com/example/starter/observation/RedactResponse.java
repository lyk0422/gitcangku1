package com.example.starter.observation;

import java.time.Instant;
import java.util.List;

/**
 * 脱敏响应：一次请求为每个附件生成的脱敏版本结果。
 *
 * @param observationId 观测记录标识
 * @param ruleVersion   本次使用的规则版本
 * @param items         各附件的脱敏结果，顺序与请求一致
 */
public record RedactResponse(
        String observationId,
        String ruleVersion,
        List<RedactItemResult> items) {

    /**
     * 单个附件的脱敏结果。
     *
     * @param attachmentId     附件唯一标识
     * @param redactionVersion 生成的脱敏版本号
     * @param status           版本状态：EFFECTIVE
     * @param createdAtUtc     创建时刻（UTC，ISO-8601）
     */
    public record RedactItemResult(
            String attachmentId,
            int redactionVersion,
            String status,
            Instant createdAtUtc) {
    }
}
