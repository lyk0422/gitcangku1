package com.example.starter.calibration.model;

import java.time.Instant;

/**
 * 放行批次状态。
 */
public enum BatchStatus {

    /** 已放行：批次当前对外可用（批内测量可能随证书撤销失去可用性）。 */
    RELEASED,

    /** 待重新放行：复核驳回后整批冻结，未驳回位置内容保留但不再对外可用。 */
    REVIEW_REQUIRED
}
