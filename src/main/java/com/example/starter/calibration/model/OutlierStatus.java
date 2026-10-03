package com.example.starter.calibration.model;

/**
 * 离群记录状态。
 */
public enum OutlierStatus {

    /** 待裁决：复核人数不足或结论相反（待仲裁），阻断批次放行。 */
    PENDING,

    /** 裁决保留：至少两名复核人一致 ACCEPT，读数保留。 */
    ACCEPTED,

    /** 裁决排除：至少两名复核人一致 REJECT，读数被排除并重算统计。 */
    REJECTED
}