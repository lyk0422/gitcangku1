package com.example.starter.domain;

/** 空域占用状态机：ACTIVE → YIELDED（被走廊强制让渡）或 DEPARTED（已起飞登记）。 */
public enum OccupationStatus {
    /** 占用中，可被让渡或登记起飞。 */
    ACTIVE,
    /** 已被应急走廊强制让渡；不自动恢复，须重新审查并显式申请。 */
    YIELDED,
    /** 已起飞登记；不可让渡，走廊创建与其时空相交时整次 422。 */
    DEPARTED
}
