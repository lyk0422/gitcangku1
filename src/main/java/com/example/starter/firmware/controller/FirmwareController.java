package com.example.starter.firmware.controller;

import com.example.starter.firmware.dto.DeviceRegisterRequest;
import com.example.starter.firmware.dto.DeviceResponse;
import com.example.starter.firmware.dto.PullRequest;
import com.example.starter.firmware.dto.RatioUpdateRequest;
import com.example.starter.firmware.dto.ReceiptRequest;
import com.example.starter.firmware.dto.ReleaseCreateRequest;
import com.example.starter.firmware.dto.ReleaseResponse;
import com.example.starter.firmware.dto.RequestIdOnlyRequest;
import com.example.starter.firmware.dto.TaskResponse;
import com.example.starter.firmware.service.DeviceService;
import com.example.starter.firmware.service.ReleaseService;
import com.example.starter.firmware.service.TaskService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 固件灰度投放 API：设备登记、发布/扩量/取消、拉取/回执与任务明细查询。
 */
@RestController
@RequestMapping("/api")
public class FirmwareController {

    private final DeviceService deviceService;
    private final ReleaseService releaseService;
    private final TaskService taskService;

    public FirmwareController(DeviceService deviceService, ReleaseService releaseService,
                              TaskService taskService) {
        this.deviceService = deviceService;
        this.releaseService = releaseService;
        this.taskService = taskService;
    }

    @PostMapping("/devices")
    public DeviceResponse registerDevice(@Valid @RequestBody DeviceRegisterRequest req) {
        return deviceService.register(req);
    }

    @GetMapping("/devices/{deviceId}")
    public DeviceResponse getDevice(@PathVariable String deviceId) {
        return deviceService.get(deviceId);
    }

    @PostMapping("/releases")
    public ReleaseResponse createRelease(@Valid @RequestBody ReleaseCreateRequest req) {
        return releaseService.create(req);
    }

    @GetMapping("/releases/{id}")
    public ReleaseResponse getRelease(@PathVariable long id) {
        return releaseService.get(id);
    }

    @PutMapping("/releases/{id}/ratio")
    public ReleaseResponse updateRatio(@PathVariable long id, @Valid @RequestBody RatioUpdateRequest req) {
        return releaseService.updateRatio(id, req);
    }

    @PostMapping("/releases/{id}/cancel")
    public ReleaseResponse cancelRelease(@PathVariable long id, @Valid @RequestBody RequestIdOnlyRequest req) {
        return releaseService.cancel(id, req);
    }

    @PostMapping("/pull")
    public TaskResponse pull(@Valid @RequestBody PullRequest req) {
        return taskService.pull(req);
    }

    @PostMapping("/tasks/{id}/receipt")
    public TaskResponse receipt(@PathVariable long id, @Valid @RequestBody ReceiptRequest req) {
        return taskService.receipt(id, req);
    }

    @GetMapping("/tasks")
    public List<TaskResponse> queryTasks(@RequestParam(required = false) Long releaseId,
                                         @RequestParam(required = false) String deviceId) {
        return taskService.query(releaseId, deviceId);
    }
}
