package com.example.starter.firmware.domain;

/**
 * 完整性失败原因代码，可区分失败分支；事件流水与任务状态均只存代码，不拼消息。
 */
public enum IntegrityFailureReason {

    /** 某分片接收摘要与登记摘要不一致。 */
    MISMATCH_DIGEST,

    /** 同序号被重复提交且摘要与首次不同。 */
    DUPLICATE_DIGEST,

    /** 全部分片到齐但聚合摘要与完整包摘要不一致（仅批量提交可能出现）。 */
    AGGREGATE_MISMATCH
}
