package com.example.starter.work.web;

import com.example.starter.work.service.WorkOrderService;
import com.example.starter.work.web.dto.AffectedPlanView;
import com.example.starter.work.web.dto.CancelRecordView;
import com.example.starter.work.web.dto.CreateSectionRequest;
import com.example.starter.work.web.dto.CreateWorkOrderRequest;
import com.example.starter.work.web.dto.SectionView;
import com.example.starter.work.web.dto.UpdateWorkOrderRequest;
import com.example.starter.work.web.dto.WorkOrderActionRequest;
import com.example.starter.work.web.dto.WorkOrderResponse;
import com.example.starter.work.web.dto.WorkWindowView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 铁路施工占用窗口 API：区段注册、施工单创建/修改/取消、
 * 施工窗口查询、受影响计划查询与取消记录查询。
 */
@Validated
@RestController
@RequestMapping("/api/v1")
public class WorkOrderController {

    private final WorkOrderService service;

    public WorkOrderController(WorkOrderService service) {
        this.service = service;
    }

    /**
     * 注册区段；已存在时返回既有区段。
     */
    @PostMapping("/sections")
    @ResponseStatus(HttpStatus.CREATED)
    public SectionView registerSection(@Valid @RequestBody CreateSectionRequest request) {
        return service.registerSection(request);
    }

    /**
     * 创建施工单；重叠施工单返回 409 并稳定列出冲突 workKey。
     */
    @PostMapping("/work-orders")
    @ResponseStatus(HttpStatus.CREATED)
    public WorkOrderResponse create(@Valid @RequestBody CreateWorkOrderRequest request) {
        return service.create(request);
    }

    /**
     * 修改施工单窗口与区段集合（版本加一）；重校验全部已发布计划，冲突则 422 不部分生效。
     */
    @PutMapping("/work-orders/{workKey}")
    public WorkOrderResponse update(@PathVariable String workKey,
                                    @Valid @RequestBody UpdateWorkOrderRequest request) {
        return service.update(workKey, request);
    }

    /**
     * 取消未开始施工单：立即释放占用并保留不可变取消记录；已开始不可取消。
     */
    @PostMapping("/work-orders/{workKey}/cancel")
    public WorkOrderResponse cancel(@PathVariable String workKey,
                                    @Valid @RequestBody WorkOrderActionRequest request) {
        return service.cancel(workKey, request);
    }

    /**
     * 施工单明细。
     */
    @GetMapping("/work-orders/{workKey}")
    public WorkOrderResponse getWorkOrder(@PathVariable String workKey) {
        return service.getWorkOrder(workKey);
    }

    /**
     * 按区段查询生效施工窗口。
     */
    @GetMapping("/work-windows")
    public List<WorkWindowView> getWorkWindows(@RequestParam @NotBlank String sectionId) {
        return service.getWorkWindows(sectionId);
    }

    /**
     * 查询受施工单影响的已发布计划。
     */
    @GetMapping("/work-orders/{workKey}/affected-plans")
    public List<AffectedPlanView> getAffectedPlans(@PathVariable String workKey) {
        return service.getAffectedPlans(workKey);
    }

    /**
     * 查询指定施工单的取消记录。
     */
    @GetMapping("/work-orders/{workKey}/cancellation")
    public CancelRecordView getCancellation(@PathVariable String workKey) {
        return service.getCancellation(workKey);
    }

    /**
     * 查询全部取消记录。
     */
    @GetMapping("/work-cancellations")
    public List<CancelRecordView> getCancellations() {
        return service.getCancellations();
    }
}
