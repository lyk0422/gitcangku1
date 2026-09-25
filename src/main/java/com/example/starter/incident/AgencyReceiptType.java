package com.example.starter.incident;

/**
 * 外部机构回执类型：CONFIRM 确认 / REJECT 拒绝。
 * 两者均为终态：同一机构在同一配置版本下至多一条终态回执，不可改写。
 * REJECT 必须携带非空说明，并使事件进入 EXTERNAL_BLOCKED。
 */
public enum AgencyReceiptType {

    /** 机构确认。 */
    CONFIRM,

    /** 机构拒绝（需非空说明）。 */
    REJECT;
}
