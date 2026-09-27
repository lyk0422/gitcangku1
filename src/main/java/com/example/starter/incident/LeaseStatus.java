package com.example.starter.incident;

/**
 * 资源授权租约状态：ACTIVE → RELEASED（回收）或 ACTIVE → EXPIRED（到期固化）。
 * RELEASED 与 EXPIRED 均为终态；EXPIRED 只在命令或访问时按注入时钟判定后写入，
 * 不依赖后台扫描。
 */
public enum LeaseStatus {

    /** 生效中：有效期按左闭右开 [assigned_at, expires_at) 解释。 */
    ACTIVE,

    /** 已回收（显式回收或任务进入 DONE/CANCELLED 时自动回收）。 */
    RELEASED,

    /** 已到期：当前时刻 >= expires_at 时被命令/访问判定并固化。 */
    EXPIRED;
}
