package com.example.starter.domain;

/** 圆形障碍档案状态：登记后有效（ACTIVE），撤销后不再参与审查（REVOKED）。 */
public enum ObstacleStatus {
    /** 有效障碍，参与航线审查。 */
    ACTIVE,
    /** 已撤销障碍，不再参与审查，记录保留不可变。 */
    REVOKED
}
