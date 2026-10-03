package com.example.starter.aging;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 熟成批次 REST 接口。所有日期为 UTC 自然日，时刻为 UTC。
 */
@RestController
@RequestMapping("/api/batches")
public class AgingController {

    private final AgingService service;

    public AgingController(AgingService service) {
        this.service = service;
    }

    public record CreateBatchRequest(@NotBlank String requestId, @NotBlank String batchCode,
            Instant storedAt) {
    }

    public record TurnRequest(@NotBlank String requestId, @NotNull LocalDate turnDate,
            @NotBlank String operator) {
    }

    public record TemperatureRequest(@NotBlank String requestId, @NotNull LocalDate recordedDate,
            @NotNull @DecimalMin("-50.0") @DecimalMax("100.0") BigDecimal celsius) {
    }

    public record VoidRequest(@NotBlank String requestId, @NotBlank String reason,
            @NotNull @DecimalMin("-50.0") @DecimalMax("100.0") BigDecimal replacementCelsius) {
    }

    public record ReleaseRequest(@NotBlank String requestId) {
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AgingBatch createBatch(@RequestBody @jakarta.validation.Valid CreateBatchRequest request) {
        return service.createBatch(request.requestId(), request.batchCode(), request.storedAt());
    }

    @PostMapping("/{batchId}/turns")
    @ResponseStatus(HttpStatus.CREATED)
    public TurnRecord confirmTurn(@PathVariable long batchId,
            @RequestBody @jakarta.validation.Valid TurnRequest request) {
        return service.confirmTurn(batchId, request.requestId(), request.turnDate(), request.operator());
    }

    @PostMapping("/{batchId}/temperatures")
    @ResponseStatus(HttpStatus.CREATED)
    public TemperatureRecord recordTemperature(@PathVariable long batchId,
            @RequestBody @jakarta.validation.Valid TemperatureRequest request) {
        return service.recordTemperature(batchId, request.requestId(), request.recordedDate(),
                request.celsius());
    }

    @PostMapping("/{batchId}/temperatures/{temperatureId}/void")
    public AgingService.VoidResult voidTemperature(@PathVariable long batchId,
            @PathVariable long temperatureId,
            @RequestBody @jakarta.validation.Valid VoidRequest request) {
        return service.voidTemperature(batchId, temperatureId, request.requestId(), request.reason(),
                request.replacementCelsius());
    }

    @PostMapping("/{batchId}/release")
    @ResponseStatus(HttpStatus.CREATED)
    public ReleaseRecord release(@PathVariable long batchId,
            @RequestBody @jakarta.validation.Valid ReleaseRequest request) {
        return service.release(batchId, request.requestId());
    }

    @GetMapping("/{batchId}")
    public Map<String, Object> batchView(@PathVariable long batchId) {
        return service.batchView(batchId);
    }
}