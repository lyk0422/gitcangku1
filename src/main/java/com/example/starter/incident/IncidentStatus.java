package com.example.starter.incident;

/**
 * 事件状态机：REPORTED → COMMANDING → CONTAINED → RESOLVED → CLOSED。
 * 只允许沿箭头前进一步，不允许跳转或回退；REPORTED → COMMANDING 仅能通过接管完成。
 * CANCELLED 为演练事件独有的取消终态，只能从 REPORTED 直接取消，不可再流转。
 * RESOLVED、CLOSED、CANCELLED 均为演练批量清理所认可的终态。
 */
public enum IncidentStatus {

    /** 已上报，尚无指挥人。 */
    REPORTED,

    /** 已接管，指挥人处置中。 */
    COMMANDING,

    /** 已控制。 */
    CONTAINED,

    /** 已解决，可由当前指挥人关闭。 */
    RESOLVED,

    /** 已关闭，终态，禁止任何写入。 */
    CLOSED,

    /** 已取消，仅演练事件可进入，终态；仅允许从 REPORTED 取消。 */
    CANCELLED;

    /**
     * 返回当前状态经状态变更接口可到达的下一状态；不可变更时返回 null。
     */
    public IncidentStatus next() {
        return switch (this) {
            case COMMANDING -> CONTAINED;
            case CONTAINED -> RESOLVED;
            case RESOLVED -> CLOSED;
            default -> null;
        };
    }

    /**
     * 是否为演练批量清理认可的终态：RESOLVED、CLOSED 或 CANCELLED。
     */
    public boolean isCleanupTerminal() {
        return this == RESOLVED || this == CLOSED || this == CANCELLED;
    }
}
