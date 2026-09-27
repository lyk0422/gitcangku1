package com.example.starter.evidence.dto;

/**
 * 迁移前置校验单项失败说明。
 *
 * @param evidenceKey 证物业务键
 * @param reason      该证物不满足前置条件的原因
 */
public record MoveItemFailure(
        String evidenceKey,
        String reason) {
}
