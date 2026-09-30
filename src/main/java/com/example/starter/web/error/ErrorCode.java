package com.example.starter.web.error;

import org.springframework.http.HttpStatus;

/**
 * 业务拒绝原因及其 HTTP 状态码。错误码字符串会原样出现在错误响应体中，便于调用方区分拒绝原因。
 */
public enum ErrorCode {

    /** 请求参数校验失败（如样本数越界、发芽数大于样本数、必填项缺失）。 */
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST),
    /** 批次不存在。 */
    BATCH_NOT_FOUND(HttpStatus.NOT_FOUND),
    /** 检测记录不存在。 */
    TEST_NOT_FOUND(HttpStatus.NOT_FOUND),
    /** 批次编号已存在。 */
    BATCH_DUPLICATE(HttpStatus.CONFLICT),
    /** 检测编号已存在于同一批次，不能重复登记。 */
    TEST_NO_DUPLICATE(HttpStatus.CONFLICT),
    /** 检测编号已归属其他批次；数据库唯一约束保证一个检测编号只能归属一个批次。 */
    CROSS_BATCH_REFERENCE(HttpStatus.CONFLICT),
    /** 检测记录已作废，不能重复作废。 */
    TEST_ALREADY_VOID(HttpStatus.CONFLICT),
    /** 有效检测不足两次，无法签发证书。 */
    INSUFFICIENT_VALID_TESTS(HttpStatus.UNPROCESSABLE_ENTITY),
    /** 最近两次有效检测中存在发芽率低于 85% 的记录。 */
    RATIO_NOT_QUALIFIED(HttpStatus.UNPROCESSABLE_ENTITY),
    /** 检测记录已被其他证书固化引用，不得重复使用。 */
    TEST_RECORD_REUSED(HttpStatus.CONFLICT),
    /** 批次没有有效证书（无证书或证书已失效），不能放行。 */
    NO_VALID_CERTIFICATE(HttpStatus.CONFLICT),
    /** 批次已放行，不能重复放行。 */
    BATCH_ALREADY_RELEASED(HttpStatus.CONFLICT),
    /** 同一 requestId 重放但请求参数与首次不一致。 */
    IDEMPOTENCY_CONFLICT(HttpStatus.CONFLICT);

    private final HttpStatus httpStatus;

    ErrorCode(HttpStatus httpStatus) {
        this.httpStatus = httpStatus;
    }

    public HttpStatus httpStatus() {
        return httpStatus;
    }
}
