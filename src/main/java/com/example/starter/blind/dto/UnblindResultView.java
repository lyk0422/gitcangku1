package com.example.starter.blind.dto;

/**
 * 揭盲结果视图：仅申请人可见，批准后包含处理代码。
 */
public record UnblindResultView(
        String unblindId,
        String experimentId,
        String participantId,
        String status,
        String treatmentCode) {
}
