package com.example.starter.observation;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 设备观测提交 API：携带设备标识与设备本地时刻提交观测，
 * 查询合并顺序下的提交列表与不可变重排记录。
 */
@RestController
@RequestMapping("/api/observations")
@Validated
public class DeviceObservationController {

    private final ClockSkewService clockSkewService;

    public DeviceObservationController(ClockSkewService clockSkewService) {
        this.clockSkewService = clockSkewService;
    }

    /**
     * 设备观测提交：保存原始本地时刻与矫正后时刻，并按合并顺序判定胜出提交。
     */
    @PostMapping("/{observationId}/device-submissions")
    public ResponseEntity<DeviceSubmissionResponse> submit(
            @PathVariable String observationId,
            @Valid @RequestBody DeviceSubmissionRequest request) {
        ClockSkewService.SubmitOutcome outcome = clockSkewService.submit(observationId, request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 按观测记录查询全部设备提交，按合并顺序（矫正后时刻，设备标识，提交标识）升序。
     */
    @GetMapping("/{observationId}/device-submissions")
    public List<DeviceSubmissionResponse> listSubmissions(@PathVariable String observationId) {
        return clockSkewService.listSubmissions(observationId).stream()
                .map(DeviceSubmissionResponse::of)
                .toList();
    }

    /**
     * 按观测记录查询不可变重排记录，按落库时间先后排序。
     */
    @GetMapping("/{observationId}/reorders")
    public List<ObservationReorder> listReorders(@PathVariable String observationId) {
        return clockSkewService.listReorders(observationId);
    }
}
