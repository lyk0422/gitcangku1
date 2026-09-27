package com.example.starter.observation;

/**
 * 调用方角色：现场工程师可登记附件，脱敏专员可登记规则、提交/撤销脱敏与物理清除，
 * 证据审阅人可读取原始摘要与全部（含已撤销）脱敏版本。
 */
public enum ActorRole {
    FIELD_ENGINEER,
    OFFICER,
    EVIDENCE_READER;

    /**
     * 大小写不敏感地解析请求头中的角色；缺失或非法值返回 null，由业务层按未授权处理（403）。
     */
    public static ActorRole fromHeader(String value) {
        if (value == null) {
            return null;
        }
        return switch (value.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "FIELD_ENGINEER" -> FIELD_ENGINEER;
            case "OFFICER" -> OFFICER;
            case "EVIDENCE_READER" -> EVIDENCE_READER;
            default -> null;
        };
    }
}
