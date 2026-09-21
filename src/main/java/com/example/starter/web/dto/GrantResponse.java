package com.example.starter.web.dto;

/**
 * 授权/撤回响应。
 *
 * @param subjectKey 主体标识
 * @param purpose    用途
 * @param epoch      授权代次
 * @param status     状态：ACTIVE 或 REVOKED
 */
public record GrantResponse(String subjectKey, String purpose, int epoch, String status) {
}
