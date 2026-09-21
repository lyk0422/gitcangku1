package com.example.starter.incident;

/**
 * 事件状态机；合法流转为 REPORTED -&gt; COMMANDING -&gt; CONTAINED -&gt; RESOLVED -&gt; CLOSED，
 * 不允许跳转或回退。
 */
public enum IncidentStatus {
    REPORTED,
    COMMANDING,
    CONTAINED,
    RESOLVED,
    CLOSED;

    /**
     * 返回合法的下一状态；已是终态（CLOSED）时返回 null。
     */
    public IncidentStatus next() {
        return switch (this) {
            case REPORTED -> COMMANDING;
            case COMMANDING -> CONTAINED;
            case CONTAINED -> RESOLVED;
            case RESOLVED -> CLOSED;
            case CLOSED -> null;
        };
    }
}
