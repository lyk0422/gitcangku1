package com.example.starter.restitution.domain;

/**
 * 裁决冻结的中选主张快照行。
 */
public record FrozenClaimRow(String claimKey, String applicant, String statement) {
}
