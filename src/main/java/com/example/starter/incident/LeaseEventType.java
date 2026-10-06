package com.example.starter.incident;

/**
 * 资源使用证据类型，对应 resource_lease_events.event_type。
 * 证据只追加不改写，按 id 升序即发生顺序。
 */
public enum LeaseEventType {

    /** 资源分配给任务（固化资源、新授权版本、时刻与操作人）。 */
    ASSIGN,

    /** 续期（固化资源、旧新授权版本、时刻与操作人）。 */
    RENEW,

    /** 到期检测：命令时按注入时钟判定租约到期并回收。 */
    EXPIRE,

    /** 资源回收：任务完成或取消时释放活动租约。 */
    RELEASE,

    /** 执行拦截：开始/继续/完成/取消时检测到授权到期，任务转为 AUTHORIZATION_EXPIRED。 */
    EXECUTION_BLOCKED;
}
