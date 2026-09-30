package com.example.starter.playout.api;

import com.example.starter.playout.api.Dtos.RegionConflict;
import org.springframework.http.HttpStatus;

import java.util.List;

/**
 * 区域编排整单拒绝异常：HTTP 422，携带每个冲突区域的原因明细。
 * 抛出时事务回滚，不留下任何部分区域占位。
 */
public class RegionalAllocationException extends ApiException {

    private final List<RegionConflict> conflicts;

    public RegionalAllocationException(String message, List<RegionConflict> conflicts) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, "REGIONAL_ALLOCATION_REJECTED", message);
        this.conflicts = List.copyOf(conflicts);
    }

    public List<RegionConflict> conflicts() {
        return conflicts;
    }
}
