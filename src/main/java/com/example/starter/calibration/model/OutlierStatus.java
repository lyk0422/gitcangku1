package com.example.starter.calibration.model;

/**
 * 离群裁决状态。PENDING 同时表示“尚无有效裁决”和“两名复核人结论相反的待仲裁”。
 */
public enum OutlierStatus {

    /** 待裁决：有效复核不足两人，或两名复核人结论相反（待仲裁，需继续追加不同复核人）。 */
    PENDING,

    /** 有效裁决为保留：两名及以上有效复核人同议 ACCEPT，读数保留。 */
    ACCEPTED,

    /** 有效裁决为排除：两名及以上有效复核人同议 REJECT，读数在裁决事务内被排除。 */
    REJECTED
}
