package com.example.starter.consent;

/**
 * 授权用途，固定为研究或个性化，两种用途互不影响。
 */
public enum Purpose {

    /** 研究。 */
    RESEARCH,

    /** 个性化。 */
    PERSONALIZATION;

    /**
     * 解析用途字符串，非法值返回 null 由调用方转换为参数错误。
     */
    public static Purpose from(String value) {
        if (value == null) {
            return null;
        }
        for (Purpose purpose : values()) {
            if (purpose.name().equalsIgnoreCase(value.trim())) {
                return purpose;
            }
        }
        return null;
    }
}
