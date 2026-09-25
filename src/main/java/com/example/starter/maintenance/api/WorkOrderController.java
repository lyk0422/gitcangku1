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

import com.example.starter.maintenance.api.dto.BatchReadingsResponse;
import com.example.starter.maintenance.api.dto.CancelEligibilityResponse;
import com.example.starter.maintenance.api.dto.CancelWorkOrderRequest;
import com.example.starter.maintenance.api.dto.CloseWorkOrderRequest;
import com.example.starter.maintenance.api.dto.CreateWorkOrderRequest;
import com.example.starter.maintenance.api.dto.MaintenanceSnapshotResponse;
import com.example.starter.maintenance.api.dto.RegisterWorkOrderReadingsRequest;
import com.example.starter.maintenance.api.dto.StartWorkOrderRequest;
import com.example.starter.maintenance.api.dto.TerminateWorkOrderRequest;
import com.example.starter.maintenance.api.dto.WorkOrderResponse;
import com.example.starter.maintenance.service.WorkOrderService;

/**
 * 保养工单 API：建单冻结基线与登记窗口，工单期内按窗口批量登记读数，
 * 关闭写入不可变快照；另提供窗口/快照/取消限制查询与读数诊断。
 */
@RestController
@RequestMapping("/api/equipment/{equipmentId}/work-orders")
public class WorkOrderController {

    private final WorkOrderService service;

    public WorkOrderController(WorkOrderService service) {
        this.service = service;
    }

    /** 创建工单：冻结当前已认证读数基线与 UTC 左闭右开窗口。 */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public WorkOrderResponse create(@PathVariable String equipmentId,
                                    @Valid @RequestBody CreateWorkOrderRequest req) {
        return service.createWorkOrder(equipmentId, req);
    }

    /** 开始工单：仅未开始工单可开始。 */
    @PostMapping("/{workOrderId}/start")
    @ResponseStatus(HttpStatus.CREATED)
    public WorkOrderResponse start(@PathVariable String equipmentId,
                                   @PathVariable String workOrderId,
                                   @Valid @RequestBody StartWorkOrderRequest req) {
        return service.startWorkOrder(equipmentId, workOrderId, req);
    }

    /** 工单期内批量登记读数：整体预校验，任一越窗/倒退/版本失配 422 且全部回滚。 */
    @PostMapping("/{workOrderId}/readings")
    @ResponseStatus(HttpStatus.CREATED)
    public BatchReadingsResponse registerReadings(@PathVariable String equipmentId,
                                                  @PathVariable String workOrderId,
                                                  @Valid @RequestBody RegisterWorkOrderReadingsRequest req) {
        return service.registerReadings(equipmentId, workOrderId, req);
    }

    /** 关闭工单：写入不可变保养状态快照。 */
    @PostMapping("/{workOrderId}/close")
    @ResponseStatus(HttpStatus.CREATED)
    public MaintenanceSnapshotResponse close(@PathVariable String equipmentId,
                                             @PathVariable String workOrderId,
                                             @Valid @RequestBody CloseWorkOrderRequest req) {
        return service.closeWorkOrder(equipmentId, workOrderId, req);
    }

    /** 取消工单：仅未开始工单允许。 */
    @PostMapping("/{workOrderId}/cancel")
    @ResponseStatus(HttpStatus.CREATED)
    public WorkOrderResponse cancel(@PathVariable String equipmentId,
                                    @PathVariable String workOrderId,
                                    @Valid @RequestBody CancelWorkOrderRequest req) {
        return service.cancelWorkOrder(equipmentId, workOrderId, req);
    }

    /** 终止工单：仅进行中工单可终止。 */
    @PostMapping("/{workOrderId}/terminate")
    @ResponseStatus(HttpStatus.CREATED)
    public WorkOrderResponse terminate(@PathVariable String equipmentId,
                                       @PathVariable String workOrderId,
                                       @Valid @RequestBody TerminateWorkOrderRequest req) {
        return service.terminateWorkOrder(equipmentId, workOrderId, req);
    }

    /** 查询工单基线与登记窗口。 */
    @GetMapping("/{workOrderId}")
    public WorkOrderResponse get(@PathVariable String equipmentId,
                                 @PathVariable String workOrderId) {
        return service.getWorkOrder(equipmentId, workOrderId);
    }

    /** 设备全部工单（按建单时刻升序）。 */
    @GetMapping
    public List<WorkOrderResponse> list(@PathVariable String equipmentId) {
        return service.listWorkOrders(equipmentId);
    }

    /** 查询工单关闭时的不可变保养状态快照。 */
    @GetMapping("/{workOrderId}/snapshot")
    public MaintenanceSnapshotResponse snapshot(@PathVariable String equipmentId,
                                                @PathVariable String workOrderId) {
        return service.getSnapshot(equipmentId, workOrderId);
    }

    /** 查询工单取消限制。 */
    @GetMapping("/{workOrderId}/cancel-eligibility")
    public CancelEligibilityResponse cancelEligibility(@PathVariable String equipmentId,
                                                       @PathVariable String workOrderId) {
        return service.getCancelEligibility(equipmentId, workOrderId);
    }

    /** 读数诊断：逐条返回窗口内读数是否越窗、低于基线或相对相邻读数倒退。 */
    @GetMapping("/{workOrderId}/reading-diagnostics")
    public List<com.example.starter.maintenance.api.dto.ReadingDiagnostic> readingDiagnostics(
            @PathVariable String equipmentId,
            @PathVariable String workOrderId) {
        return service.diagnoseReadings(equipmentId, workOrderId);
    }
}
