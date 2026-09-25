package com.example.starter.observation;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 设备时钟偏移 API：偏移登记、偏移秒数修改与设备偏移明细查询。
 * 登记/修改在同一事务内重建受影响提交的矫正后时刻与合并顺序。
 */
@RestController
@RequestMapping("/api/devices")
@Validated
public class DeviceClockController {

    private final ClockSkewService clockSkewService;

    public DeviceClockController(ClockSkewService clockSkewService) {
        this.clockSkewService = clockSkewService;
    }

    /**
     * 登记设备偏移记录：生效起始时刻与既有记录重复（区间重叠）返回 409。
     */
    @PostMapping("/{deviceId}/offsets")
    public ResponseEntity<OffsetChangeResponse> register(@PathVariable String deviceId,
                                                         @Valid @RequestBody OffsetChangeRequest request) {
        ClockSkewService.OffsetOutcome outcome = clockSkewService.registerOffset(deviceId, request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 修改既有偏移记录的偏移秒数；记录不存在返回 404。
     */
    @PutMapping("/{deviceId}/offsets")
    public ResponseEntity<OffsetChangeResponse> modify(@PathVariable String deviceId,
                                                       @Valid @RequestBody OffsetChangeRequest request) {
        ClockSkewService.OffsetOutcome outcome = clockSkewService.modifyOffset(deviceId, request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 查询设备偏移明细，按生效起始时刻升序。
     */
    @GetMapping("/{deviceId}/offsets")
    public List<DeviceOffsetEntry> listOffsets(@PathVariable String deviceId) {
        return clockSkewService.listOffsets(deviceId);
    }
}
