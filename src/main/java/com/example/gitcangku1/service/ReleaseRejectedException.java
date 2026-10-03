package com.example.gitcangku1.service;

import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;

/** 整批放行被拒绝，按原因分组返回有问题的卷号。 */
public class ReleaseRejectedException extends RuntimeException {

    private final Map<String, List<Integer>> rejectedRolls;

    public ReleaseRejectedException(Map<String, List<Integer>> rejectedRolls) {
        super("批次不满足放行条件: " + rejectedRolls);
        this.rejectedRolls = rejectedRolls;
    }

    public HttpStatus status() {
        return HttpStatus.UNPROCESSABLE_ENTITY;
    }

    /** 键为原因(MISSING/UNDECIDED_RECHECK/OUT_OF_TOLERANCE/STALE_TARGET_VERSION)，值为卷号列表。 */
    public Map<String, List<Integer>> rejectedRolls() {
        return rejectedRolls;
    }
}