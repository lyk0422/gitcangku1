package com.example.starter.consent;

/**
 * 操作人上下文：由请求头 X-Actor-Id 与 X-Actor-Role 解析。
 *
 * @param id             操作人标识
 * @param retentionRole  是否具有保留权限角色（RETENTION_OFFICER）
 */
public record Actor(String id, boolean retentionRole) {

    /** 保留权限角色名。 */
    public static final String ROLE_RETENTION_OFFICER = "RETENTION_OFFICER";
}
