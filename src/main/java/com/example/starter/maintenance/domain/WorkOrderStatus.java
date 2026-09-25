package com.example.starter.maintenance.domain;

/**
 * 保养工单状态机。
 *
 * <p>允许流转：CREATED → STARTED（开始）；CREATED → CANCELLED（取消，仅未开始）；
 * CREATED/STARTED → TERMINATED（终止）；STARTED → CLOSED（关闭并写入不可变快照）。
 * 终态（CLOSED/CANCELLED/TERMINATED）不允许任何写操作。
 */
public enum WorkOrderStatus {
    /** 已创建未开始：可开始、取消或终止；不允许登记读数。 */
    CREATED,
    /** 进行中：可登记读数、关闭或终止；不可取消。 */
    STARTED,
    /** 已关闭：已写入不可变保养状态快照。 */
    CLOSED,
    /** 已取消：仅未开始工单可取消。 */
    CANCELLED,
    /** 已终止：按规则终止的工单。 */
    TERMINATED
}
