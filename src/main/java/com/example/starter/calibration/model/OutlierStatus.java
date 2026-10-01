package com.example.starter.calibration.model;

/**
 * 离群记录状态。
 */
public enum OutlierStatus {

    /** 待裁决：复核人数不足两人，或已复核结论相反（待仲裁）；批次放行被阻断。 */
    PENDING,

    /** 已接受：两名复核角色一致 ACCEPT，读数保留并计入批次统计。 */
    ACCEPTED,

    /** 已拒绝：两名复核角色一致 REJECT，读数被排除并重算批次统计。 */
    REJECTED
}