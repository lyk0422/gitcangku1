package com.example.starter.observation;

import java.util.Locale;

/**
 * 请求者角色：由请求头 X-Role 携带，决定脱敏写权限与证据读权限。
 *
 * <ul>
 *   <li>PUBLIC：未携带头时的默认角色，仅可访问公开查询；</li>
 *   <li>REDACTION_OFFICER：可提交脱敏、撤销脱敏、登记/撤销规则与物理清除附件；</li>
 *   <li>EVIDENCE_READER：具备证据权限，可按版本读取原始摘要与全部脱敏版本。</li>
 * </ul>
 */
public enum RequesterRole {
    PUBLIC,
    REDACTION_OFFICER,
    EVIDENCE_READER;

    /**
     * 大小写不敏感解析请求头；缺省为 PUBLIC，无法识别的角色返回 null（由业务层按未授权 403 处理）。
     */
    public static RequesterRole fromHeader(String value) {
        if (value == null || value.isBlank()) {
            return PUBLIC;
        }
        return switch (value.trim().toUpperCase(Locale.ROOT)) {
            case "PUBLIC", "VIEWER" -> PUBLIC;
            case "REDACTION_OFFICER" -> REDACTION_OFFICER;
            case "EVIDENCE_READER" -> EVIDENCE_READER;
            default -> null;
        };
    }
}
