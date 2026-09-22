package com.example.starter.firmware.web;

import com.example.starter.firmware.dto.RegisterDeviceRequest;
import com.example.starter.firmware.service.ApiResult;
import com.example.starter.firmware.service.DeviceService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 设备登记接口。
 */
@RestController
@RequestMapping("/api/devices")
public class DeviceController {

    private final DeviceService deviceService;

    public DeviceController(DeviceService deviceService) {
        this.deviceService = deviceService;
    }

    /**
     * 登记设备：型号与分桶号登记后不可变。
     */
    @PostMapping
    public ResponseEntity<Object> register(@Valid @RequestBody RegisterDeviceRequest request) {
        ApiResult result = deviceService.register(request);
        return ResponseEntity.status(result.status()).body(result.body());
    }
}
