package com.example.starter.incident;

/**
 * 资源授权证据类型，对应 resource_authorization_events.event_type。
 * 证据只增不改，按 id 排序，字段、时间精度与 null 含义保持稳定。
 */
public enum ResourceEventType {

    /** 资源分配（oldAuthorizationVersion 为空，newAuthorizationVersion 为初始版本）。 */
    ASSIGN,

    /** 到期检测（按注入时钟在命令时判定，oldAuthorizationVersion 为到期时版本，new 为空）。 */
    EXPIRY_DETECTED,

    /** 续期（old/newAuthorizationVersion 分别为续期前后版本）。 */
    RENEW,

    /** 执行拦截（授权已到期时拒绝开始/继续/完成，attemptedAction 记录被拦截动作）。 */
    EXECUTION_BLOCKED;
}
