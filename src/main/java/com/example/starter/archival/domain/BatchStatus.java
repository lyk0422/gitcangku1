package com.example.starter.archival.domain;

/**
 * 脱酸批次状态。
 */
public enum BatchStatus {

    /** 进行中：可入组、登记读数、归还页面。 */
    OPEN,

    /** 质检退回或封存校验未通过：批次未封存，仍可追加处理轮次后重新封存。 */
    REJECTED,

    /** 已封存：只读，占用已释放，不允许任何修改。 */
    SEALED
}
