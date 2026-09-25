package com.example.starter.firmware.api;

import com.example.starter.firmware.service.DeviceService;
import com.example.starter.firmware.service.TaskService;
import com.example.starter.firmware.repo.IncompatibleRecordRepository;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 设备登记、查询、固件拉取与不兼容记录查询。
 */
@RestController
@RequestMapping("/api/devices")
public class DeviceController {

    private final DeviceService deviceService;
    private final TaskService taskService;
    private final IncompatibleRecordRepository incompatibleRecordRepository;

    public DeviceController(DeviceService deviceService, TaskService taskService,
                            IncompatibleRecordRepository incompatibleRecordRepository) {
        this.deviceService = deviceService;
        this.taskService = taskService;
        this.incompatibleRecordRepository = incompatibleRecordRepository;
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

    @GetMapping("/{deviceId}/incompatible-records")
    public IncompatibleListResponse incompatibleRecords(@PathVariable String deviceId) {
        deviceService.get(deviceId);
        return new IncompatibleListResponse(incompatibleRecordRepository.findByDevice(deviceId));
    }
}
