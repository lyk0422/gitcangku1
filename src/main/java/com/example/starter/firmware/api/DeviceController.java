package com.example.starter.firmware.api;

import com.example.starter.firmware.service.DeviceService;
import com.example.starter.firmware.service.TagService;
import com.example.starter.firmware.service.TaskService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 设备登记、查询、标签批量修改与变更历史、固件拉取。
 */
@RestController
@RequestMapping("/api/devices")
public class DeviceController {

    private final DeviceService deviceService;
    private final TaskService taskService;
    private final TagService tagService;

    public DeviceController(DeviceService deviceService, TaskService taskService,
                            TagService tagService) {
        this.deviceService = deviceService;
        this.taskService = taskService;
        this.tagService = tagService;
    }

    @PostMapping
    public DeviceView register(@Valid @RequestBody RegisterDeviceRequest request) {
        return deviceService.register(request);
    }

    @GetMapping("/{deviceId}")
    public DeviceView get(@PathVariable String deviceId) {
        return deviceService.get(deviceId);
    }

    @PostMapping("/{deviceId}/pull")
    public PullResponse pull(@PathVariable String deviceId, @Valid @RequestBody RequestIdBody request) {
        return taskService.pull(deviceId, request.requestId());
    }

    @PostMapping("/tags/batch")
    public DeviceTagBatchResponse batchUpdateTags(@Valid @RequestBody TagBatchUpdateRequest request) {
        return tagService.batchUpdate(request);
    }

    /**
     * 标签变更历史（只读）。from/to 为 UTC ISO-8601 时刻，区间左闭右开 [from, to)。
     */
    @GetMapping("/tags/history")
    public TagHistoryResponse tagHistory(@RequestParam(required = false) String from,
                                         @RequestParam(required = false) String to) {
        return tagService.history(from, to);
    }
}
