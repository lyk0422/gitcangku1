package com.example.starter.incident;

/**
 * 事件状态机：REPORTED → COMMANDING → CONTAINED → RESOLVED → CLOSED。
 * 只允许沿箭头前进一步，不允许跳转或回退；REPORTED → COMMANDING 仅能通过接管完成。
 * CANCELLED（已取消）为终态，只能通过显式取消接口从任一非终态进入，不可再写入。
 * RESOLVED / CLOSED / CANCELLED 均为演练批量清理认可的终态。
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

    /** 已取消，终态，仅演练/真实事件经显式取消后进入，禁止任何写入。 */
    CANCELLED;

    /**
     * 返回当前状态经状态变更接口可到达的下一状态；不可变更时返回 null。
     * 取消不经过逐级状态机，因此不在此列。
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
     * 是否为终态：RESOLVED、CLOSED、CANCELLED 终态不可再进行业务写入，
     * 也是演练批次清理校验所认可的终结状态。
     */
    public boolean isTerminal() {
        return this == RESOLVED || this == CLOSED || this == CANCELLED;
    }
}
