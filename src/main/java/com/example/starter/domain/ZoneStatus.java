package com.example.starter.domain;

/** 禁飞区状态：只能创建（ACTIVE）或撤销（REVOKED）。 */
public enum ZoneStatus {
    /** 有效禁飞区，参与航线审核。 */
    ACTIVE,
    /** 已撤销禁飞区，不再参与审核，记录保留不可变。 */
    REVOKED
}
