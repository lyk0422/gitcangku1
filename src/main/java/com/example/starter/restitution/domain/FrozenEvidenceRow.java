package com.example.starter.restitution.domain;

/**
 * 裁决冻结的有效证据快照行。
 */
public record FrozenEvidenceRow(String claimKey, String evidenceKey, String summary, long version) {
}
