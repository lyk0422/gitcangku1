package com.example.starter.sampling;

/**
 * 采样观测状态：RECEIVED 已接收未合并 / MERGED 已合并 / PERMIT_REVIEW 许可撤销待复核。
 */
public enum ObservationStatus {
    RECEIVED,
    MERGED,
    PERMIT_REVIEW
}
