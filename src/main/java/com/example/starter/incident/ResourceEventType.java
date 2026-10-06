package com.example.starter.incident;

/**
 * 资源租约证据事件类型。证据只追加、不改写，按 id 升序排列。
 */
public enum ResourceEventType {

    /** 分配：固化资源、初始授权版本、分配时刻与分配人。 */
    ASSIGN,

    /** 续期：固化旧/新授权版本、续期时刻与续期人。 */
    RENEW,

    /** 到期检测：命令路径首次按时钟判定到期并将任务置为 AUTHORIZATION_EXPIRED。 */
    EXPIRE,

    /** 执行拦截：开始/继续执行/完成因授权到期被拒绝。 */
    EXECUTE_DENIED,

    /** 资源回收：任务完成或取消时释放租约。 */
    RELEASE;
}
