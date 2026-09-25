package com.example.starter.batch;

import org.springframework.http.HttpStatus;

import java.util.List;

/**
 * 条件核销被前置条件阻止时的 422 异常：除原因外携带当前未核销子项标识（按 seq 排序），
 * 用于到期降级（CONDITION_EXPIRED）或批次已被召回（BATCH_RECALLED）两类裁决。
 */
public class ConditionBlockedException extends ApiException {

    private final String reason;
    private final List<String> openItems;

    private ConditionBlockedException(String reason, String message, List<String> openItems) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, "CONDITION_BLOCKED", message);
        this.reason = reason;
        this.openItems = List.copyOf(openItems);
    }

    public static ConditionBlockedException expired(List<String> openItems) {
        return new ConditionBlockedException("CONDITION_EXPIRED",
                "条件有效期已到，仍有未核销子项，批次已降级为不可用", openItems);
    }

    public static ConditionBlockedException recalled(List<String> openItems) {
        return new ConditionBlockedException("BATCH_RECALLED",
                "批次已被召回，条件核销不能继续", openItems);
    }

    public String reason() {
        return reason;
    }

    public List<String> openItems() {
        return openItems;
    }
}
