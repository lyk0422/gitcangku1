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
import com.example.starter.maintenance.api.dto.BatchIsolationResponse;
import com.example.starter.maintenance.api.dto.ConfirmLockPointRequest;
import com.example.starter.maintenance.api.dto.CreateIsolationRequest;
import com.example.starter.maintenance.api.dto.IsolationHistoryEvent;
import com.example.starter.maintenance.api.dto.IsolationOrderResponse;
import com.example.starter.maintenance.api.dto.IsolatedReadingRejectionView;
import com.example.starter.maintenance.api.dto.ReleaseIsolationRequest;
import com.example.starter.maintenance.service.IsolationService;

/**
 * 设备能源隔离 API：隔离令创建（单设备/批量）、锁定点双角色确认、逐一解除，
 * 以及明细、历史与诊断查询。隔离生效期间设备新增运行读数返回 409 并固化拒绝证据。
 */
@RestController
@RequestMapping("/api")
public class IsolationController {

    private final IsolationService service;

    public IsolationController(IsolationService service) {
        this.service = service;
    }

    /** 创建单设备能源隔离令：携带完整锁定点集合；重复锁定点/设备已隔离返回 422。 */
    @PostMapping("/equipment/{equipmentId}/isolation-orders")
    @ResponseStatus(HttpStatus.CREATED)
    public IsolationOrderResponse createIsolation(@PathVariable String equipmentId,
                                                  @Valid @RequestBody CreateIsolationRequest req) {
        return service.createIsolation(equipmentId, req);
    }

    /** 批量隔离多设备：先校验全部设备的完整锁定点集合，任一失败整批不生效（单事务）。 */
    @PostMapping("/isolation-orders/batch")
    @ResponseStatus(HttpStatus.CREATED)
    public BatchIsolationResponse createBatch(@Valid @RequestBody BatchCreateIsolationRequest req) {
        return service.createBatch(req);
    }

    /** 确认锁定点：每个锁定点须两名不同维护角色分别确认；同角色双确认返回 422。 */
    @PostMapping("/isolation-orders/{orderId}/confirmations")
    @ResponseStatus(HttpStatus.CREATED)
    public IsolationOrderResponse confirmLockPoint(@PathVariable String orderId,
                                                   @Valid @RequestBody ConfirmLockPointRequest req) {
        return service.confirmLockPoint(orderId, req);
    }

    /** 解除隔离：须逐一解除全部锁定点并填写原因；部分解除返回 422。 */
    @PostMapping("/isolation-orders/{orderId}/release")
    @ResponseStatus(HttpStatus.CREATED)
    public IsolationOrderResponse release(@PathVariable String orderId,
                                          @Valid @RequestBody ReleaseIsolationRequest req) {
        return service.release(orderId, req);
    }

    /** 隔离令明细：锁定点、确认与解除明细，含实际值/要求值/差额，稳定排序。 */
    @GetMapping("/isolation-orders/{orderId}")
    public IsolationOrderResponse getOrder(@PathVariable String orderId) {
        return service.getOrder(orderId);
    }

    /** 设备隔离令列表（按创建时刻升序）。 */
    @GetMapping("/equipment/{equipmentId}/isolation-orders")
    public List<IsolationOrderResponse> listOrders(@PathVariable String equipmentId) {
        return service.listOrders(equipmentId);
    }

    /** 设备隔离历史（创建/生效/解除事件，按事件序号稳定排序）。 */
    @GetMapping("/equipment/{equipmentId}/isolation-history")
    public List<IsolationHistoryEvent> history(@PathVariable String equipmentId) {
        return service.history(equipmentId);
    }

    /** 诊断：隔离生效期被拒的新增读数证据（按拒绝时刻升序）。 */
    @GetMapping("/equipment/{equipmentId}/isolated-reading-rejections")
    public List<IsolatedReadingRejectionView> rejections(@PathVariable String equipmentId) {
        return service.rejections(equipmentId);
    }
}
