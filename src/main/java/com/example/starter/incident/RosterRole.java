package com.example.starter.incident;

/**
 * 名册席位角色：受影响事件当时的指挥官，或提案指定的一名安全审核员。
 * 同一人员可同时承担多个席位（如既是某事件指挥官又是安全审核员）。
 */
public enum RosterRole {

    /** 受影响事件在提案创建时的现任指挥官（席位绑定具体事件）。 */
    COMMANDER,

    /** 提案创建时指定的一名安全审核员（全提案一名）。 */
    SAFETY_REVIEWER;
}
