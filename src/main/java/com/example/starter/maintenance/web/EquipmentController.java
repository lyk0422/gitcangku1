package com.example.starter.maintenance.web;

import com.example.starter.maintenance.service.MaintenanceService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * 设备工时保养判定 API。
 */
@RestController
@RequestMapping("/api/equipment")
public class EquipmentController {

    /**
     * 设备登记请求。
     */
    public record RegisterEquipmentRequest(
            @NotBlank String requestId,
            @NotBlank String equipmentId,
            @NotNull @Positive Integer maintenanceIntervalMinutes) {
    }

    /**
     * 新增读数请求。
     */
    public record AddReadingRequest(
            @NotBlank String requestId,
            @NotNull Integer expectedVersion,
            @NotBlank String readingId,
            @NotNull Instant sampledAt,
            @NotNull @PositiveOrZero Long accumulatedMinutes) {
    }

    /**
     * 修订读数请求：只改变累计分钟。
     */
    public record ReviseReadingRequest(
            @NotBlank String requestId,
            @NotNull Integer expectedVersion,
            @NotNull @PositiveOrZero Long accumulatedMinutes) {
    }

    /**
     * 完成保养请求：锚点为现存读数及其当前修订号。
     */
    public record CompleteMaintenanceRequest(
            @NotBlank String requestId,
            @NotNull Integer expectedVersion,
            @NotBlank String anchorReadingId,
            @NotNull @Positive Integer anchorRevisionNo) {
    }

    private final MaintenanceService service;

    public EquipmentController(MaintenanceService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<String> register(@Valid @RequestBody RegisterEquipmentRequest request) {
        return respond(service.registerEquipment(request.requestId(), request.equipmentId(),
                request.maintenanceIntervalMinutes()));
    }

    @PostMapping("/{equipmentId}/readings")
    public ResponseEntity<String> addReading(@PathVariable String equipmentId,
                                             @Valid @RequestBody AddReadingRequest request) {
        return respond(service.addReading(request.requestId(), equipmentId, request.expectedVersion(),
                request.readingId(), request.sampledAt(), request.accumulatedMinutes()));
    }

    @PostMapping("/{equipmentId}/readings/{readingId}/revisions")
    public ResponseEntity<String> reviseReading(@PathVariable String equipmentId,
                                                @PathVariable String readingId,
                                                @Valid @RequestBody ReviseReadingRequest request) {
        return respond(service.reviseReading(request.requestId(), equipmentId, readingId,
                request.expectedVersion(), request.accumulatedMinutes()));
    }

    @PostMapping("/{equipmentId}/maintenances")
    public ResponseEntity<String> completeMaintenance(@PathVariable String equipmentId,
                                                      @Valid @RequestBody CompleteMaintenanceRequest request) {
        return respond(service.completeMaintenance(request.requestId(), equipmentId, request.expectedVersion(),
                request.anchorReadingId(), request.anchorRevisionNo()));
    }

    @GetMapping("/{equipmentId}/status")
    public MaintenanceService.StatusView status(@PathVariable String equipmentId) {
        return service.getStatus(equipmentId);
    }

    @GetMapping("/{equipmentId}/history")
    public MaintenanceService.HistoryView history(@PathVariable String equipmentId) {
        return service.getHistory(equipmentId);
    }

    private static ResponseEntity<String> respond(MaintenanceService.ServiceResponse response) {
        return ResponseEntity.status(response.status())
                .contentType(MediaType.APPLICATION_JSON)
                .body(response.body());
    }
}
