package com.example.starter.api.dto;

import java.util.List;

/**
 * 发布门禁 422 响应体：列出本次发布命中但缺少有效豁免的全部制品/漏洞。
 *
 * @param blocked 全部阻断项，任一存在即整次拒绝发布
 */
public record GateBlockedResponse(
        String error,
        String message,
        List<VulnerabilityHitResponse> blocked) {
}
