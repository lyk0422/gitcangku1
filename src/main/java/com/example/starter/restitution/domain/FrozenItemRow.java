package com.example.starter.restitution.domain;

/**
 * 裁决冻结的藏品归属行。
 */
public record FrozenItemRow(String itemNo, String claimKey, String applicant) {
}
