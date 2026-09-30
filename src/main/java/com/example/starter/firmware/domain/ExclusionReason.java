package com.example.starter.firmware.domain;

/**
 * 硬件不兼容排除原因码。除 PROFILE_MISSING 外均表示画像存在但不满足对应门禁条件。
 */
public enum ExclusionReason {

    /** 设备从未提交过硬件画像。 */
    PROFILE_MISSING,
    /** 画像缺少型号字段。 */
    MODEL_MISSING,
    /** 画像缺少内存字段。 */
    MEMORY_MISSING,
    /** 画像型号不在版本声明的型号集合内。 */
    MODEL_NOT_REQUIRED,
    /** 画像内存低于版本声明的最低内存。 */
    MEMORY_INSUFFICIENT,
    /** 画像缺少版本声明的全部或部分必需能力标签。 */
    REQUIRED_TAG_MISSING
}
