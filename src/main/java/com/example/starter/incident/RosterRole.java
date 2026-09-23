package com.example.starter.incident;

/**
 * 名册席位角色：COMMANDER 为受影响事件创建时的指挥官，SAFETY_REVIEWER 为安全审核员。
 * 同一人员可兼任多个席位，仍只投一票，一票同时满足其全部席位。
 */
public enum RosterRole {

    /** 受影响事件指挥官席位（创建时冻结，交接不改写）。 */
    COMMANDER,

    /** 安全审核员席位。 */
    SAFETY_REVIEWER;
}
