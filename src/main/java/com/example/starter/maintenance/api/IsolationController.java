package com.example.starter.maintenance.api;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.maintenance.api.dto.BatchIsolationRequest;
import com.example.starter.maintenance.api.dto.BatchIsolationResponse;
import com.example.starter.maintenance.api.dto.ConfirmLockPointRequest;
import com.example.starter.maintenance.api.dto.CreateIsolationRequest;
import com.example.starter.maintenance.api.dto.DeactivateEquipmentRequest;
import com.example.starter.maintenance.api.dto.EquipmentDeactivationResponse;
import com.example.starter.maintenance.api.dto.FinalizeReleaseRequest;
import com.example.starter.maintenance.api.dto.IsolationDiagnosticsResponse;
import com.example.starter.maintenance.api.dto.IsolationEventView;
import com.example.starter.maintenance.api.dto.IsolationOrderResponse;
import com.example.starter.maintenance.api.dto.ReleaseLockPointRequest;
import com.example.starter.maintenance.api.dto.RejectedReadingView;
import com.example.starter.maintenance.service.EquipmentService;

/**
 * 设备能源隔离 API。
 *
 * <p>创建隔离令后为 PENDING；每个锁定点须由 ISOLATOR、VERIFIER 两个不同维护角色分别确认，
 * 全部确认完成令转 EFFECTIVE。生效期间新增运行读数返回 409，保养检查仍可提交；
 * 锁定点逐一解除后才能整令解除，整令解除后读数恢复提交。所有操作按 requestId 幂等。</p>
 */
@RestController
public class IsolationController {

    private final EquipmentService service;

    public IsolationController(EquipmentService service) {
        this.service = service;
    }

    /** 批量隔离多设备：先校验整批完整锁定点集合与后态，任一失败整批不生效。 */
    @PostMapping("/api/isolation/batch")
    @ResponseStatus(HttpStatus.CREATED)
    public BatchIsolationResponse createBatch(@Valid @RequestBody BatchIsolationRequest req) {
        return service.createBatchIsolation(req);
    }

    /** 对单台设备创建能源隔离令（锁定点集合随令固化）。 */
    @PostMapping("/api/equipment/{equipmentId}/isolation/orders")
    @ResponseStatus(HttpStatus.CREATED)
    public IsolationOrderResponse createOrder(@PathVariable String equipmentId,
                                              @Valid @RequestBody CreateIsolationRequest req) {
        return service.createIsolation(equipmentId, req);
    }

    /** 一名操作人以一个维护角色确认当前活动隔离令的一个锁定点。 */
    @PostMapping("/api/equipment/{equipmentId}/isolation/lock-points/{lockPointId}/confirmations")
    @ResponseStatus(HttpStatus.CREATED)
    public IsolationOrderResponse confirmLockPoint(@PathVariable String equipmentId,
                                                   @PathVariable String lockPointId,
                                                   @Valid @RequestBody ConfirmLockPointRequest req) {
        return service.confirmLockPoint(equipmentId, lockPointId, req);
    }

    /** 生效隔离令的锁定点逐一解除（必须填写本次解除原因）。 */
    @PostMapping("/api/equipment/{equipmentId}/isolation/lock-points/{lockPointId}/releases")
    @ResponseStatus(HttpStatus.CREATED)
    public IsolationOrderResponse releaseLockPoint(@PathVariable String equipmentId,
                                                   @PathVariable String lockPointId,
                                                   @Valid @RequestBody ReleaseLockPointRequest req) {
        return service.releaseLockPoint(equipmentId, lockPointId, req);
    }

    /** 整令解除：所有锁定点均已逐一解除后才能成功，部分解除返回 422。 */
    @PostMapping("/api/equipment/{equipmentId}/isolation/finalize-release")
    @ResponseStatus(HttpStatus.CREATED)
    public IsolationOrderResponse finalizeRelease(@PathVariable String equipmentId,
                                                  @Valid @RequestBody FinalizeReleaseRequest req) {
        return service.finalizeRelease(equipmentId, req);
    }

    /** 停用设备：停用后不可再隔离或提交工时/保养；存在未结束隔离令时返回 422。 */
    @PostMapping("/api/equipment/{equipmentId}/deactivation")
    @ResponseStatus(HttpStatus.CREATED)
    public EquipmentDeactivationResponse deactivate(@PathVariable String equipmentId,
                                                    @Valid @RequestBody DeactivateEquipmentRequest req) {
        return service.deactivateEquipment(equipmentId, req);
    }

    /** 设备全部隔离令历史（按令标识升序）。 */
    @GetMapping("/api/equipment/{equipmentId}/isolation/orders")
    public List<IsolationOrderResponse> listOrders(@PathVariable String equipmentId) {
        return service.listIsolationOrders(equipmentId);
    }

    /** 单条隔离令明细（含锁定点确认/解除操作人与时刻）。 */
    @GetMapping("/api/equipment/{equipmentId}/isolation/orders/{orderId}")
    public IsolationOrderResponse getOrder(@PathVariable String equipmentId,
                                           @PathVariable long orderId) {
        return service.getIsolationOrder(equipmentId, orderId);
    }

    /** 隔离审计事件流（只追加，按事件序号升序）。 */
    @GetMapping("/api/equipment/{equipmentId}/isolation/events")
    public List<IsolationEventView> listEvents(@PathVariable String equipmentId) {
        return service.listIsolationEvents(equipmentId);
    }

    /** 隔离期间被拒读数证据（按拒绝序号升序）。 */
    @GetMapping("/api/equipment/{equipmentId}/isolation/rejected-readings")
    public List<RejectedReadingView> listRejectedReadings(@PathVariable String equipmentId) {
        return service.listRejectedReadings(equipmentId);
    }

    /**
     * 诊断：实际值/要求值/差额与比例；时间区间左闭右开 [from, to)，
     * 参数为 UTC ISO-8601 时刻，省略任一端表示该侧不限制。查询不修改状态。
     */
    @GetMapping("/api/equipment/{equipmentId}/isolation/diagnostics")
    public IsolationDiagnosticsResponse diagnostics(
            @PathVariable String equipmentId,
            @RequestParam(name = "from", required = false) String from,
            @RequestParam(name = "to", required = false) String to) {
        return service.isolationDiagnostics(equipmentId, parseInstant(from, "from"), parseInstant(to, "to"));
    }

    private static Instant parseInstant(String value, String param) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("INVALID_TIME_RANGE",
                    "参数 " + param + " 须为 UTC ISO-8601 时刻，如 2026-01-01T00:00:00Z");
        }
    }
}
