package com.example.starter.support;

import com.example.starter.api.dto.VulnerabilityHitResponse;

import java.util.List;

/**
 * 发布门禁拒绝异常：HTTP 422，携带本次命中但缺少有效豁免的全部制品/漏洞。
 */
public class GateBlockedException extends ApiException {

    private final transient List<VulnerabilityHitResponse> blocked;

    public GateBlockedException(List<VulnerabilityHitResponse> blocked) {
        super(422, "VULNERABILITY_GATE_BLOCKED", "存在未豁免的 CRITICAL 漏洞命中，禁止发布");
        this.blocked = List.copyOf(blocked);
    }

    public List<VulnerabilityHitResponse> getBlocked() {
        return blocked;
    }
}
