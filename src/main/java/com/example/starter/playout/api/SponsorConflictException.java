package com.example.starter.playout.api;

import com.example.starter.playout.api.Dtos.SponsorConflictDetail;

import java.util.List;

/**
 * 赞助排他冲突异常：HTTP 409，携带本次校验发现的全部冲突明细（频道、时段、冲突素材、窗口与交集时长）。
 */
public class SponsorConflictException extends RuntimeException {

    private final List<SponsorConflictDetail> conflicts;

    public SponsorConflictException(String message, List<SponsorConflictDetail> conflicts) {
        super(message);
        this.conflicts = List.copyOf(conflicts);
    }

    public List<SponsorConflictDetail> conflicts() {
        return conflicts;
    }
}