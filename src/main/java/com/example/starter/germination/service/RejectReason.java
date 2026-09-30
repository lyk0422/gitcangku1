package com.example.starter.germination.service;

/**
 * 业务拒绝原因编码，随 4xx 响应返回，便于调用方明确区分拒绝原因。
 */
public enum RejectReason {
    /** 批次不存在。 */
    BATCH_NOT_FOUND,
    /** 样本数不在 100-400 区间。 */
    SAMPLE_OUT_OF_RANGE,
    /** 发芽粒数超过样本数或为负。 */
    GERMINATED_EXCEEDS_SAMPLE,
    /** 检测编号不存在。 */
    TEST_NOT_FOUND,
    /** 检测编号在同批次已登记过（重复使用检测记录）。 */
    TEST_NO_DUPLICATED,
    /** 检测编号已归属其他批次（同一检测编号只归属一个批次）。 */
    TEST_BELONGS_TO_OTHER_BATCH,
    /** 检测记录已作废，重复作废或不能再使用。 */
    TEST_ALREADY_VOID,
    /** 有效检测不足两次，不能签发证书。 */
    NOT_ENOUGH_VALID_TESTS,
    /** 最近两次有效检测存在发芽率低于 85% 的记录。 */
    RATIO_BELOW_THRESHOLD,
    /** 该批次最新证书仍有效，不可重复签发（失效历史证书之后可凭新检测重新签发，旧证书留档）。 */
    CERTIFICATE_ALREADY_EXISTS,
    /** 证书不存在，不能放行。 */
    CERTIFICATE_NOT_FOUND,
    /** 证书已失效，放行冲突。 */
    CERTIFICATE_INVALID,
    /** 批次已放行，操作冲突。 */
    BATCH_ALREADY_RELEASED,
    /** 同一 requestId 重放但请求参数与首次不一致。 */
    IDEMPOTENT_PARAM_MISMATCH
}
