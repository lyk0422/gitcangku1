package com.example.starter.calibration.api;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.calibration.api.dto.ReleaseSnapshotResponse;
import com.example.starter.calibration.repo.ReleaseSnapshotRepository;
import com.example.starter.calibration.service.DtoMapper;

/**
 * 放行诊断接口：按 UTC 时间区间 [from, to) 左闭右开查询固化快照，查询不修改状态，
 * 按放行时间、快照 ID 升序，排序稳定。
 */
@RestController
public class DiagnosticsController {

    /** 单次诊断查询返回上限。 */
    static final int WINDOW_LIMIT = 500;

    private final ReleaseSnapshotRepository snapshots;

    public DiagnosticsController(ReleaseSnapshotRepository snapshots) {
        this.snapshots = snapshots;
    }

    /**
     * 快照窗口诊断：GET /api/diagnostics/snapshots?from=...&amp;to=...（ISO-8601，按 UTC 归一）。
     * 区间左闭右开；参数非法 400。
     */
    @GetMapping("/api/diagnostics/snapshots")
    public List<ReleaseSnapshotResponse> snapshotWindow(@RequestParam String from,
                                                        @RequestParam String to) {
        Instant fromInstant = parseInstant(from, "from");
        Instant toInstant = parseInstant(to, "to");
        if (!fromInstant.isBefore(toInstant)) {
            throw ApiException.badRequest("from 必须早于 to（区间左闭右开）");
        }
        return snapshots.findWindow(fromInstant, toInstant, WINDOW_LIMIT).stream()
                .map(DtoMapper::toResponse)
                .toList();
    }

    private static Instant parseInstant(String value, String field) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest(field + " 不能为空");
        }
        try {
            return OffsetDateTime.parse(value.trim(), DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant();
        } catch (DateTimeParseException ex) {
            throw ApiException.badRequest(field + " 必须为带时区的 ISO-8601 时间");
        }
    }
}
