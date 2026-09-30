package com.example.starter.archival.domain;

/**
 * 档案页在某批次内的在组状态。
 */
public enum MemberStatus {

    /** 在组：页面正被该未封存批次处理占用。 */
    ACTIVE,

    /** 已从未封存批次归还（调拨出库）：不再占用，封存时不参与校验。 */
    RELEASED
}
