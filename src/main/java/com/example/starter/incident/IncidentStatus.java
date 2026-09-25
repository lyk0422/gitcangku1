package com.example.starter.incident;

/**
 * 事件状态机：REPORTED → COMMANDING → CONTAINED → RESOLVED → CLOSED；
 * 此外任何非终态可经重复事件合并进入 MERGED 终态。
 * 主流程只允许沿箭头前进一步，不允许跳转或回退；REPORTED → COMMANDING 仅能通过接管完成；
 * MERGED 仅能通过合并进入，不占用主流程箭头。
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

    /** 已作为重复事件被并入其他事件，终态；任务与阻塞边已迁移到存续事件。 */
    MERGED;

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
}
