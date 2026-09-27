package com.example.starter.maintenance.api;

import java.util.List;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.maintenance.api.dto.BatchCreateIsolationRequest;
import com.example.starter.maintenance.api.dto.BatchCreateIsolationResponse;
import com.example.starter.maintenance.api.dto.ConfirmLockPointsRequest;
import com.example.starter.maintenance.api.dto.CreateIsolationRequest;
import com.example.starter.maintenance.api.dto.IsolationOrderResponse;
import com.example.starter.maintenance.api.dto.ReleaseIsolationRequest;
import com.example.starter.maintenance.api.dto.RejectedReadingView;
import com.example.starter.maintenance.service.IsolationService;

/**
 * 设备能源隔离令 API：创建（单个/批量）、双角色确认锁定点、逐一解除，
 * 以及明细、历史、事件流水与被拒读数诊断查询。所有时刻为 UTC。
 */
@RestController
@RequestMapping("/api/equipment")
public class IsolationController {

    private final IsolationService service;

    public IsolationController(IsolationService service) {
        this.service = service;
    }

    /** 创建能源隔离令：一次性固化完整锁定点集合；设备已停用 422，锁定点重复 422。 */
    @PostMapping("/{equipmentId}/isolations")
    @ResponseStatus(HttpStatus.CREATED)
    public IsolationOrderResponse createIsolation(@PathVariable String equipmentId,
                                                  @Valid @RequestBody CreateIsolationRequest req) {
        return service.createIsolation(equipmentId, req);
    }

    /** 批量创建隔离令：先校验所有设备的完整锁定点集合，任一失败整批不生效。 */
    @PostMapping("/isolations/batch")
    @ResponseStatus(HttpStatus.CREATED)
    public BatchCreateIsolationResponse batchCreateIsolations(
            @Valid @RequestBody BatchCreateIsolationRequest req) {
        return service.batchCreateIsolations(req);
    }

    /** 维护角色确认锁定点：同一角色对同一点仅可确认一次；全部点经两名角色确认后令生效。 */
    @PostMapping("/{equipmentId}/isolations/{orderId}/confirmations")
    @ResponseStatus(HttpStatus.CREATED)
    public IsolationOrderResponse confirmLockPoints(@PathVariable String equipmentId,
                                                    @PathVariable String orderId,
                                                    @Valid @RequestBody ConfirmLockPointsRequest req) {
        return service.confirmLockPoints(equipmentId, orderId, req);
    }

    /** 解除隔离令：须对所有锁定点逐一给出原因；部分解除 422 且不产生任何变更。 */
    @PostMapping("/{equipmentId}/isolations/{orderId}/release")
    public IsolationOrderResponse releaseIsolation(@PathVariable String equipmentId,
                                                   @PathVariable String orderId,
                                                   @Valid @RequestBody ReleaseIsolationRequest req) {
        return service.releaseIsolation(equipmentId, orderId, req);
    }

    /** 隔离令明细：含锁定点快照、角色确认证据与解除证据。 */
    @GetMapping("/{equipmentId}/isolations/{orderId}")
    public IsolationOrderResponse getOrder(@PathVariable String equipmentId,
                                           @PathVariable String orderId) {
        return service.getOrder(equipmentId, orderId);
    }

    /** 设备隔离令历史（按创建时刻与标识升序，稳定排序）。 */
    @GetMapping("/{equipmentId}/isolations")
    public List<IsolationOrderResponse> listOrders(@PathVariable String equipmentId) {
        return service.listOrders(equipmentId);
    }

    /** 设备在隔离生效期间被拒的新增读数诊断证据（按拒绝时刻升序）。 */
    @GetMapping("/{equipmentId}/rejected-readings")
    public List<RejectedReadingView> listRejectedReadings(@PathVariable String equipmentId) {
        return service.listRejectedReadings(equipmentId);
    }
}
