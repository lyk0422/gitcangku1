package com.example.starter.observation;

/**
 * 请求角色：决定脱敏相关接口的访问权限。
 *
 * <p>PUBLIC：公开查询，只能读取最新有效脱敏版本；
 * EVIDENCE：证据权限，可按版本读取原始与脱敏摘要、读取诊断；
 * REDACTOR：脱敏操作者，可登记附件/规则、提交脱敏与撤销（同时具备证据权限）；
 * ADMIN：管理员，具备全部权限并可执行附件内容物理清除。
 */
public enum ActorRole {
    PUBLIC,
    EVIDENCE,
    REDACTOR,
    ADMIN;

    /**
     * 解析角色标识（大小写不敏感）；null、空白或未知值一律视为 PUBLIC。
     */
    public static ActorRole fromValue(String value) {
        if (value == null || value.isBlank()) {
            return PUBLIC;
        }
        for (ActorRole role : values()) {
            if (role.name().equalsIgnoreCase(value.trim())) {
                return role;
            }
        }
        return PUBLIC;
    }

    /**
     * 是否具备证据权限（按版本读取原始与脱敏摘要、诊断查询）。
     */
    public boolean hasEvidenceAccess() {
        return this == EVIDENCE || this == REDACTOR || this == ADMIN;
    }

    /**
     * 是否具备脱敏写操作权限（登记附件/规则、提交脱敏、撤销脱敏）。
     */
    public boolean hasRedactionWriteAccess() {
        return this == REDACTOR || this == ADMIN;
    }

    /**
     * 是否具备物理清除附件内容的权限。
     */
    public boolean hasPurgeAccess() {
        return this == ADMIN;
    }
}
