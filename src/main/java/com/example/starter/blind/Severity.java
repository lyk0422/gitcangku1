package com.example.starter.blind;

/**
 * 不良事件严重度；仅 SEVERE 会把对应分配标记为 URGENT_REVIEW。
 */
public enum Severity {
    /** 轻度：不改变分配状态。 */
    MILD,
    /** 中度：不改变分配状态。 */
    MODERATE,
    /** 严重：自动标记分配为 URGENT_REVIEW，可触发紧急揭盲。 */
    SEVERE;

    /**
     * 解析请求中的严重度文本；空值或非法取值按 400 处理。
     */
    public static Severity fromText(String text) {
        if (text == null || text.isBlank()) {
            throw ApiException.badRequest("severity 不能为空，取值 MILD / MODERATE / SEVERE");
        }
        try {
            return Severity.valueOf(text.trim());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("severity 仅允许 MILD / MODERATE / SEVERE");
        }
    }

    /** 是否应触发 URGENT_REVIEW 标记。 */
    public boolean marksUrgentReview() {
        return this == SEVERE;
    }
}
