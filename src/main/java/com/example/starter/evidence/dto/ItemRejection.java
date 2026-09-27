package com.example.starter.evidence.dto;

/**
 * 迁移申请前置校验的逐项失败说明。
 *
 * @param evidenceKey 证物业务键
 * @param reason      该证物不满足前置条件的原因
 */
public record ItemRejection(
        String evidenceKey,
        String reason) {
}
