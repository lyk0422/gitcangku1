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

import com.example.starter.maintenance.api.dto.CreateWorkOrderRequest;
import com.example.starter.maintenance.api.dto.RegisterWorkOrderReadingsRequest;
import com.example.starter.maintenance.api.dto.WorkOrderOperationRequest;
import com.example.starter.maintenance.api.dto.WorkOrderResponse;
import com.example.starter.maintenance.service.WorkOrderService;

/**
 * 保养工单锁定 API：建单冻结基线与登记窗口；进行期间读数受窗口与基线约束；
 * 关闭写入不可变保养状态快照；取消仅限未开始工单。
 */
@RestController
@RequestMapping("/api/equipment/{equipmentId}/work-orders")
public class WorkOrderController {

    private final WorkOrderService service;

    public WorkOrderController(WorkOrderService service) {
        this.service = service;
    }

    /** 建单：冻结当前已认证读数为基线，锁定 UTC 左闭右开登记窗口（结束须晚于开始）。 */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public WorkOrderResponse create(@PathVariable String equipmentId,
                                    @Valid @RequestBody CreateWorkOrderRequest req) {
        return service.create(equipmentId, req);
    }

    /** 开始工单：仅 CREATED 可开始；开始后读数登记受窗口与基线约束。 */
    @PostMapping("/{workOrderKey}/start")
    public WorkOrderResponse start(@PathVariable String equipmentId,
                                   @PathVariable String workOrderKey,
                                   @Valid @RequestBody WorkOrderOperationRequest req) {
        return service.start(equipmentId, workOrderKey, req);
    }

    /** 批量登记读数：整体预校验（最终序列、窗口、基线、版本），任一失败 422 并全部回滚。 */
    @PostMapping("/{workOrderKey}/readings")
    @ResponseStatus(HttpStatus.CREATED)
    public WorkOrderResponse registerReadings(@PathVariable String equipmentId,
                                              @PathVariable String workOrderKey,
                                              @Valid @RequestBody RegisterWorkOrderReadingsRequest req) {
        return service.registerReadings(equipmentId, workOrderKey, req);
    }

    /** 关闭工单：写入不可变保养状态快照（基线、最后有效读数、关闭时刻）。 */
    @PostMapping("/{workOrderKey}/close")
    public WorkOrderResponse close(@PathVariable String equipmentId,
                                   @PathVariable String workOrderKey,
                                   @Valid @RequestBody WorkOrderOperationRequest req) {
        return service.close(equipmentId, workOrderKey, req);
    }

    /** 取消工单：仅未开始（CREATED）允许；已开始工单必须关闭或终止。 */
    @PostMapping("/{workOrderKey}/cancel")
    public WorkOrderResponse cancel(@PathVariable String equipmentId,
                                    @PathVariable String workOrderKey,
                                    @Valid @RequestBody WorkOrderOperationRequest req) {
        return service.cancel(equipmentId, workOrderKey, req);
    }

    /** 终止工单：未完结（CREATED/STARTED）工单可终止；终态工单返回 409。 */
    @PostMapping("/{workOrderKey}/terminate")
    public WorkOrderResponse terminate(@PathVariable String equipmentId,
                                       @PathVariable String workOrderKey,
                                       @Valid @RequestBody WorkOrderOperationRequest req) {
        return service.terminate(equipmentId, workOrderKey, req);
    }

    /** 查询工单：基线、登记窗口、读数诊断、保养状态快照与取消限制。 */
    @GetMapping("/{workOrderKey}")
    public WorkOrderResponse get(@PathVariable String equipmentId,
                                 @PathVariable String workOrderKey) {
        return service.get(equipmentId, workOrderKey);
    }

    /** 设备工单列表（按建单时刻升序）。 */
    @GetMapping
    public List<WorkOrderResponse> list(@PathVariable String equipmentId) {
        return service.list(equipmentId);
    }
}
