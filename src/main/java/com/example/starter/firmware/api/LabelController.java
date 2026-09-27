package com.example.starter.firmware.api;

import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.service.DeviceLabelService;
import com.example.starter.firmware.service.LabelDictService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.format.DateTimeParseException;

/**
 * 设备标签批量修改与标签字典诊断查询。
 */
@RestController
@RequestMapping("/api/labels")
public class LabelController {

    private final DeviceLabelService deviceLabelService;
    private final LabelDictService labelDictService;

    public LabelController(DeviceLabelService deviceLabelService, LabelDictService labelDictService) {
        this.deviceLabelService = deviceLabelService;
        this.labelDictService = labelDictService;
    }

    /**
     * 批量整体替换多台设备标签；整批预检通过后单事务提交。
     */
    @PostMapping("/batch-update")
    public BatchLabelUpdateResponse batchUpdate(@Valid @RequestBody BatchLabelUpdateRequest request) {
        return deviceLabelService.updateBatch(request);
    }

    /**
     * 标签字典只读查询：当前版本与全部规范化标签。
     */
    @GetMapping("/dict")
    public LabelDictView dict() {
        return new LabelDictView(labelDictService.currentVersion(), labelDictService.allTags());
    }

    /**
     * 标签修订事件诊断（只读）：按修订时刻 UTC 做左闭右开 [fromUtc, toUtc) 过滤。
     */
    @GetMapping("/revisions")
    public LabelRevisionListResponse revisions(@RequestParam(required = false) String fromUtc,
                                               @RequestParam(required = false) String toUtc) {
        String from = parseUtc("fromUtc", fromUtc);
        String to = parseUtc("toUtc", toUtc);
        if (from != null && to != null && from.compareTo(to) > 0) {
            throw ApiException.badRequest("INVALID_TIME_RANGE",
                    "fromUtc 不能晚于 toUtc（区间左闭右开 [fromUtc, toUtc)）");
        }
        var revisions = labelDictService.revisionsBetween(from, to).stream()
                .map(LabelRevisionView::of).toList();
        return new LabelRevisionListResponse(from, to, revisions.size(), revisions);
    }

    private String parseUtc(String name, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value.trim()).toString();
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("INVALID_UTC_TIME",
                    name + " 必须为 UTC ISO-8601 时刻（如 2026-09-28T10:15:30Z）: " + value);
        }
    }
}
