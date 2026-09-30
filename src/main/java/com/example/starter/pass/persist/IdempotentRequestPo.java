package com.example.starter.pass.persist;

import java.time.Instant;

/**
 * 幂等请求记录：同一 requestId 的分配/完成回报只能成功一次。
 *
 * <p>payload_hash 为首次请求规范化参数的摘要；重放同参返回首次结果，异参冲突。</p>
 */
public class IdempotentRequestPo {

    /** 业务请求 ID（主键）。 */
    private String requestId;

    /** 操作类型：ALLOCATE（分配）/ COMPLETE（完成回报）。 */
    private String operation;

    /** 首次请求规范化参数的 SHA-256 十六进制摘要。 */
    private String payloadHash;

    /** 关联的时隙分配主键。 */
    private long allocationId;

    /** 首次落库时间，UTC。 */
    private Instant createdAt;

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public String getOperation() {
        return operation;
    }

    public void setOperation(String operation) {
        this.operation = operation;
    }

    public String getPayloadHash() {
        return payloadHash;
    }

    public void setPayloadHash(String payloadHash) {
        this.payloadHash = payloadHash;
    }

    public long getAllocationId() {
        return allocationId;
    }

    public void setAllocationId(long allocationId) {
        this.allocationId = allocationId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
