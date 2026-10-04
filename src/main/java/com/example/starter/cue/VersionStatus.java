package com.example.starter.cue;

/** 编排版本状态。 */
public enum VersionStatus {
    /** 草稿：可含未校验数据，不可被排练冻结 */
    DRAFT,
    /** 已冻结：全部提示点校验通过，可绑定排练 */
    FROZEN
}