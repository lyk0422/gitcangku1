package com.example.starter.blind.dto;

/**
 * 揭盲申请视图：不包含处理代码。
 */
public record UnblindRequestView(
        String unblindId,
        String experimentId,
        String participantId,
        String applicantId,
        String status) {
}
