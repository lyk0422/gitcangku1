package com.example.starter.incident;

/**
 * 规范化后的单条依赖边变更。
 * 边方向：fromIncidentKey 事件的任务依赖 toIncidentKey 事件（阻塞关系）。
 * 记录列表按 (op, from, to) 排序后即换序等价、结构化去重。
 */
public record EdgeChange(EdgeOp op, String fromIncidentKey, String toIncidentKey)
        implements Comparable<EdgeChange> {

    @Override
    public int compareTo(EdgeChange other) {
        int byOp = op.name().compareTo(other.op.name());
        if (byOp != 0) {
            return byOp;
        }
        int byFrom = fromIncidentKey.compareTo(other.fromIncidentKey);
        if (byFrom != 0) {
            return byFrom;
        }
        return toIncidentKey.compareTo(other.toIncidentKey);
    }
}
