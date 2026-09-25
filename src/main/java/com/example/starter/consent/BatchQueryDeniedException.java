package com.example.starter.consent;

import java.util.List;

import com.example.starter.consent.dto.SubjectDenial;

/**
 * 批次查询阻断异常：任一主体校验失败时抛出，携带按主体标识排序的稳定原因列表，
 * 响应 403 且不返回任何数据、不留下半成品状态。
 */
public class BatchQueryDeniedException extends RuntimeException {

    static final String CODE_BATCH_QUERY_DENIED = "BATCH_QUERY_DENIED";

    private final transient List<SubjectDenial> reasons;

    public BatchQueryDeniedException(List<SubjectDenial> reasons) {
        super("批量查询被阻断，存在未通过委托校验的主体");
        this.reasons = List.copyOf(reasons);
    }

    public List<SubjectDenial> getReasons() {
        return reasons;
    }
}
