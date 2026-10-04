package com.example.starter.domain;

/** 运动间隔许可状态：只能创建（ACTIVE）或撤销（REVOKED）。 */
public enum PermitStatus {
    /** 有效许可，参与运动间隔校验与活动许可计数。 */
    ACTIVE,
    /** 已撤销许可，释放未来占用、不再参与间隔校验，历史轨迹保留不可变。 */
    REVOKED
}