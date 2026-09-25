package com.example.starter.incident;

/**
 * 外部机构配置版本状态：ACTIVE 当前生效 / REPLACED 已被新版本替换。
 * 每事件至多一条 ACTIVE；REPLACED 版本的回执仅归属旧版本，不参与新版本门禁。
 */
public enum AgencyConfigStatus {

    /** 当前生效版本。 */
    ACTIVE,

    /** 已被更新版本替换的历史版本。 */
    REPLACED;
}
