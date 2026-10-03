package com.example.starter.calibration.model;

/**
 * 离群记录状态。
 */
public enum OutlierStatus {

    /** 待裁决：复核不足两人结论一致，或结论相反保持待仲裁。 */
    PENDING,

    /** 已接受：两名及以上复核人一致 ACCEPT，读数保留。 */
    ACCEPTED,

    /** 已拒绝：两名及以上复核人一致 REJECT，读数被排除并重算统计。 */
    REJECTED
}