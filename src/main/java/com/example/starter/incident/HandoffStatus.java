package com.example.starter.incident;

/**
 * 跨事件互助交接状态：ACTIVE 为租约生效中（含已开始任务在租约到期后继续占用资源的情形）；
 * SETTLED 为已结算归还，终态。仅允许 ACTIVE→SETTLED，结算记录不可变。
 */
public enum HandoffStatus {

    /** 交接生效中：资源责任在目标事件侧。 */
    ACTIVE,

    /** 已结算：资源责任归还来源事件，终态。 */
    SETTLED;
}
