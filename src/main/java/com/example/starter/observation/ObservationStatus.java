package com.example.starter.observation;

/**
 * 许可采样观测的业务状态。旧的直接创建路径（POST /api/observations）不携带许可，状态为 null。
 */
public enum ObservationStatus {

    /**
     * 已接收未合并：采样提交成功，许可计数已扣减。
     */
    RECEIVED,

    /**
     * 已合并：经离线合并（批量或单条路径）产生了新版本或合并结果与当前一致后进入。
     */
    MERGED,

    /**
     * 许可复核中：观测尚未合并时其命中许可被撤销，转入人工复核并写入复核快照；终态由人工流程处理。
     */
    PERMIT_REVIEW
}
