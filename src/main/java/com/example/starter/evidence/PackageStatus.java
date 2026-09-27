package com.example.starter.evidence;

/**
 * 披露包版本状态。版本行一旦生成即不可变，状态仅随交付/撤销流转。
 * GENERATED 未交付（可交付或撤销）；DELIVERED 已交付（终态，不可撤销，只能再建更高修订包）；
 * REVOKED 已撤销（终态，撤销原因留痕）。
 */
public enum PackageStatus {

    /** 未交付：建包或增删生成新版本后的初始状态，可交付或撤销。 */
    GENERATED,

    /** 已交付：终态，不可撤销；发现问题只能创建更高修订包。 */
    DELIVERED,

    /** 已撤销：终态，仅未交付包可撤销，撤销必须填写原因。 */
    REVOKED
}
