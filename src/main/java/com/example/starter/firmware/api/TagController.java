package com.example.starter.firmware.api;

import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.service.DeviceTagService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * 设备标签批量修改与标签字典只读诊断。时间查询参数为 UTC ISO-8601，区间左闭右开。
 */
@RestController
public class TagController {

    private final DeviceTagService deviceTagService;

    public TagController(DeviceTagService deviceTagService) {
        this.deviceTagService = deviceTagService;
    }

    /**
     * 批量标签修改：整批核对后一次事务提交，返回逐项最终状态与要求/实际/差额数量。
     */
    @PostMapping("/api/devices/tags/batch")
    public BatchTagsResponse batchModify(@Valid @RequestBody BatchTagsRequest request) {
        return deviceTagService.batchModify(request);
    }

    @GetMapping("/api/devices/{deviceId}/tags")
    public DeviceTagsView deviceTags(@PathVariable String deviceId) {
        return deviceTagService.getTags(deviceId);
    }

    @GetMapping("/api/tags/dict")
    public TagDictView dict() {
        return deviceTagService.dictVersion();
    }

    /**
     * 修订历史（只读），fromUtc/toUtc 省略表示不设该侧边界；区间 [fromUtc, toUtc)。
     */
    @GetMapping("/api/tags/revisions")
    public List<TagRevisionView> revisions(@RequestParam(required = false) String fromUtc,
                                           @RequestParam(required = false) String toUtc) {
        return deviceTagService.history(parseInstant(fromUtc, "fromUtc"), parseInstant(toUtc, "toUtc"));
    }

    private Instant parseInstant(String value, String param) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("INVALID_TIME",
                    "参数 " + param + " 不是合法的 UTC ISO-8601 时刻: " + value);
        }
    }
}
