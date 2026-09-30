package com.example.starter.playout;

import com.example.starter.playout.api.ApiException;
import com.example.starter.playout.api.Dtos.RegionConflictResponse;
import org.springframework.http.HttpStatus;

import java.util.List;

/**
 * 区域编排整单不可处理异常：固定 422，携带每个区域时段的冲突原因，
 * 由全局异常处理器转换为统一错误响应（conflicts 为额外字段）。
 * 整次提交在同一事务内回滚，不留下部分区域占位。
 */
public class RegionScheduleException extends ApiException {

    private final List<RegionConflictResponse> conflicts;

    public RegionScheduleException(String code, String message, List<RegionConflictResponse> conflicts) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
        this.conflicts = List.copyOf(conflicts);
    }

    public List<RegionConflictResponse> conflicts() {
        return conflicts;
    }
}
