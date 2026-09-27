package com.example.starter.incident;

/**
 * 资源授权证据事件类型。事件只追加不改写，固化资源、旧新授权版本、UTC 时刻与操作人。
 */
public enum ResourceEventType {

    /** 资源分配给任务（携带初始授权版本与 UTC 到期时刻）。 */
    ASSIGN,

    /** 续期：由不同于原分配人的授权角色提交新版本与新到期时刻。 */
    RENEW,

    /** 到期判定：命令或访问时发现当前时刻 >= expires_at，固化任务与租约状态。 */
    EXPIRE,

    /** 资源回收（显式回收或任务进入 DONE/CANCELLED 时自动回收）。 */
    RELEASE,

    /** 任务开始执行（授权校验通过）。 */
    EXECUTE_START,

    /** 任务继续执行（授权校验通过）。 */
    EXECUTE_CONTINUE,

    /** 任务完成（授权校验通过）。 */
    EXECUTE_COMPLETE,

    /** 执行命令被到期拦截（开始/继续/完成时判定授权已到期）。 */
    EXECUTE_INTERCEPT;
}
