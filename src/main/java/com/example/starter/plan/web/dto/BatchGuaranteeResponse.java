package com.example.starter.plan.web.dto;

import java.util.List;

/**
 * 批量保障改签响应：批次键与逐条改签决策证据（全部成功才提交）。
 */
public record BatchGuaranteeResponse(String batchRequestKey, List<ConnectionDecisionView> items) {
}
