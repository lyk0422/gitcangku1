package com.example.starter.consent;

/**
 * 批量操作中的单条拒绝原因：target 为主体标识或委托指纹，reason 为稳定原因码。
 *
 * @param target 被拒绝的对象标识（主体标识或委托指纹）
 * @param reason 稳定原因码，如 DELEGATION_VERSION_CONFLICT、DELEGATION_EXPIRED
 */
public record RejectionReason(String target, String reason) {
}
