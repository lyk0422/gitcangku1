package com.example.starter.observation;

/**
 * 调用方角色（经请求头 X-Actor-Role 传入，缺省为 PUBLIC）。
 *
 * <ul>
 *   <li>PUBLIC：公开查询，只能读取最新有效（ACTIVE）脱敏版本；</li>
 *   <li>REDACTOR：登记附件、提交/撤销脱敏；</li>
 *   <li>EVIDENCE：证据权限，可按版本读取原始摘要与全部脱敏版本、固化证据；</li>
 *   <li>ADMIN：登记/撤销脱敏规则、物理清除附件。</li>
 * </ul>
 */
public enum ActorRole {
    PUBLIC,
    REDACTOR,
    EVIDENCE,
    ADMIN;

    /**
     * 解析请求头原文：空白或缺失视为 PUBLIC；无法识别的角色返回 null（由调用方按 403 拒绝）。
     */
    public static ActorRole fromHeader(String raw) {
        if (raw == null || raw.isBlank()) {
            return PUBLIC;
        }
        try {
            return ActorRole.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
