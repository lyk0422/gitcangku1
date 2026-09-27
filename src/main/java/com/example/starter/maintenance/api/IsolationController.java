package com.example.starter.maintenance.api;

import java.util.List;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.maintenance.api.dto.BatchIsolationRequest;
import com.example.starter.maintenance.api.dto.BatchIsolationResponse;
import com.example.starter.maintenance.api.dto.ConfirmIsolationRequest;
import com.example.starter.maintenance.api.dto.CreateIsolationRequest;
import com.example.starter.maintenance.api.dto.DecommissionEquipmentRequest;
import com.example.starter.maintenance.api.dto.EquipmentResponse;
import com.example.starter.maintenance.api.dto.IsolationResponse;
import com.example.starter.maintenance.api.dto.RejectedReadingView;
import com.example.starter.maintenance.api.dto.ReleaseLockPointRequest;
import com.example.starter.maintenance.service.IsolationService;

/**
 * 设备能源隔离 API：隔离令创建（单设备/批量）、两名不同维护角色确认、锁定点逐一解除、
 * 隔离历史与被拒读数诊断查询、设备停用。所有时刻 UTC，隔离生效区间左闭右开。
 */
@RestController
public class IsolationController {

    private final IsolationService service;

    public IsolationController(IsolationService service) {
        this.service = service;
    }

    /** 单设备创建能源隔离令：须给出完整锁定点集合，创建后为 PENDING。 */
    @PostMapping("/api/equipment/{equipmentId}/isolations")
    @ResponseStatus(HttpStatus.CREATED)
    public IsolationResponse createIsolation(@PathVariable String equipmentId,
                                             @Valid @RequestBody CreateIsolationRequest req) {
        return service.createIsolation(equipmentId, req);
    }

    /** 批量隔离：先校验所有设备完整后态，任一失败整批不生效。 */
    @PostMapping("/api/equipment/isolations/batch")
    @ResponseStatus(HttpStatus.CREATED)
    public BatchIsolationResponse batchCreateIsolations(@Valid @RequestBody BatchIsolationRequest req) {
        return service.batchCreateIsolations(req);
    }

    /** 隔离令确认：两名不同维护角色分别确认，第二名确认后隔离生效。 */
    @PostMapping("/api/equipment/{equipmentId}/isolations/{isolationId}/confirmations")
    @ResponseStatus(HttpStatus.CREATED)
    public IsolationResponse confirmIsolation(@PathVariable String equipmentId,
                                              @PathVariable long isolationId,
                                              @Valid @RequestBody ConfirmIsolationRequest req) {
        return service.confirmIsolation(equipmentId, isolationId, req);
    }

    /** 逐一解除锁定点：每次必须写原因；最后一个解除后隔离令整体解除。 */
    @PostMapping("/api/equipment/{equipmentId}/isolations/{isolationId}/lock-points/{lockPointId}/release")
    @ResponseStatus(HttpStatus.CREATED)
    public IsolationResponse releaseLockPoint(@PathVariable String equipmentId,
                                              @PathVariable long isolationId,
                                              @PathVariable String lockPointId,
                                              @Valid @RequestBody ReleaseLockPointRequest req) {
        return service.releaseLockPoint(equipmentId, isolationId, lockPointId, req);
    }

    /** 停用设备：已停用或存在未完成隔离令返回 422。 */
    @PostMapping("/api/equipment/{equipmentId}/decommission")
    @ResponseStatus(HttpStatus.CREATED)
    public EquipmentResponse decommission(@PathVariable String equipmentId,
                                          @Valid @RequestBody DecommissionEquipmentRequest req) {
        return service.decommission(equipmentId, req);
    }

    /** 隔离令明细（含锁定点与确认人，排序稳定，查询不改状态）。 */
    @GetMapping("/api/equipment/{equipmentId}/isolations/{isolationId}")
    public IsolationResponse getIsolation(@PathVariable String equipmentId,
                                          @PathVariable long isolationId) {
        return service.getIsolation(equipmentId, isolationId);
    }

    /** 设备隔离历史：按创建时刻升序。 */
    @GetMapping("/api/equipment/{equipmentId}/isolations")
    public List<IsolationResponse> listIsolations(@PathVariable String equipmentId) {
        return service.listIsolations(equipmentId);
    }

    /** 被隔离拒绝的新增读数诊断证据：按拒绝时刻升序。 */
    @GetMapping("/api/equipment/{equipmentId}/rejected-readings")
    public List<RejectedReadingView> listRejectedReadings(@PathVariable String equipmentId) {
        return service.listRejectedReadings(equipmentId);
    }
}
