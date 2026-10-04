package com.example.starter.api;

import org.springframework.http.HttpStatus;

import java.util.List;

/**
 * 运动间隔不足（HTTP 422）：新许可与既有许可在共同有效区间内的
 * 最小距离小于 10 米，不新增许可，并返回冲突许可 ID 列表。
 */
public class PermitConflictException extends ApiException {

    /** 与新许可冲突的既有许可 ID（字典序去重）。 */
    private final List<String> conflictingPermitIds;

    public PermitConflictException(List<String> conflictingPermitIds) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, "PERMIT_SEPARATION_CONFLICT",
                "共同有效区间内最小距离不足 10 米，冲突许可: " + conflictingPermitIds);
        this.conflictingPermitIds = List.copyOf(conflictingPermitIds);
    }

    public List<String> conflictingPermitIds() {
        return conflictingPermitIds;
    }
}