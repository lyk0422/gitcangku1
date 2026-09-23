package com.example.starter.restitution.web.dto;

/**
 * 冻结主张快照视图。
 */
public record FrozenClaimView(
        String claimKey,
        String applicant,
        String statement
) {
}
