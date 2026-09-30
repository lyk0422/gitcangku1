package com.example.starter.model;

import com.example.starter.domain.OperationType;
import com.example.starter.domain.RequestStatus;

import java.time.Instant;

/**
 * 幂等调用记录：同一 (requestId, operation) 仅保存首次成功提交的参数指纹与结果。
 */
public class IdempotentCall {

    /** 调用方幂等键。 */
    private String requestId;

    /** 操作类型。 */
    private OperationType operation;

    /** 请求参数指纹（SHA-256 十六进制），与首次不一致返回 409。 */
    private String requestFingerprint;

    /** 首次结果关联的申请编号。 */
    private String resultRequestNo;

    /** 首次结果的申请状态快照。 */
    private RequestStatus resultStatus;

    /** 首次成功提交时间，UTC。 */
    private Instant createdAt;

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public OperationType getOperation() {
        return operation;
    }

    public void setOperation(OperationType operation) {
        this.operation = operation;
    }

    public String getRequestFingerprint() {
        return requestFingerprint;
    }

    public void setRequestFingerprint(String requestFingerprint) {
        this.requestFingerprint = requestFingerprint;
    }

    public String getResultRequestNo() {
        return resultRequestNo;
    }

    public void setResultRequestNo(String resultRequestNo) {
        this.resultRequestNo = resultRequestNo;
    }

    public RequestStatus getResultStatus() {
        return resultStatus;
    }

    public void setResultStatus(RequestStatus resultStatus) {
        this.resultStatus = resultStatus;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
