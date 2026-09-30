package com.example.starter.tag.domain;

import java.time.Instant;

/**
 * 幂等请求键。附着与交接携带 requestId；同一 requestId 同参重放返回首次结果，
 * 异参冲突返回 409。任一事务失败不写入该键，即失败不消费请求键。
 */
public class IdempotentRequest {

    private Long id;
    /** 客户端提供的幂等键。 */
    private String requestId;
    /** 请求类型，如 ATTACH / HANDOFF_INITIATE。 */
    private String requestType;
    /** 首次请求参数的规范化摘要。 */
    private String requestHash;
    /** 首次成功请求产生的业务记录主键。 */
    private Long resultId;
    private Instant createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public String getRequestType() {
        return requestType;
    }

    public void setRequestType(String requestType) {
        this.requestType = requestType;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public void setRequestHash(String requestHash) {
        this.requestHash = requestHash;
    }

    public Long getResultId() {
        return resultId;
    }

    public void setResultId(Long resultId) {
        this.resultId = resultId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
