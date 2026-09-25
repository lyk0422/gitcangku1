package com.example.starter.incident;

/**
 * 外部机构回执类型。两种回执均为终态：同一机构每个配置版本至多一条。
 */
public enum AgencyAckType {

    /** 确认。 */
    CONFIRM,

    /** 拒绝（必须填写拒绝说明）；任一必需机构拒绝即触发事件 EXTERNAL_BLOCKED。 */
    REJECT
}
