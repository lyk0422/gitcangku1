package com.example.starter.plan.model;

/**
 * 乘务资格版本状态：ACTIVE 当前生效（发布门禁唯一认可的状态）；
 * SUPERSEDED 已被同人员同线路的新版本取代；REVOKED 已被撤销（撤销并发裁决后，
 * 基于该版本的新发布一律拒绝；已发布计划保留原快照不受影响）。
 */
public enum QualificationStatus {
    ACTIVE,
    SUPERSEDED,
    REVOKED
}
