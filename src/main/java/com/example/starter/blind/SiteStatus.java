package com.example.starter.blind;

/**
 * 研究中心启用状态。
 */
public enum SiteStatus {
    /** 未启用：启用申请尚未经负责人与独立复核人双人确认，冻结任何分配。 */
    INACTIVE,
    /** 已启用：当前配置版本已一次性冻结为快照，分配服务只读该快照。 */
    ACTIVE
}
