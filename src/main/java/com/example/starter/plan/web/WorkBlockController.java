package com.example.starter.plan.web;

import com.example.starter.plan.service.WorkBlockService;
import com.example.starter.plan.web.dto.BatchRescheduleRequest;
import com.example.starter.plan.web.dto.BatchRescheduleResponse;
import com.example.starter.plan.web.dto.RegisterWorkBlockRequest;
import com.example.starter.plan.web.dto.WorkBlockDetailResponse;
import com.example.starter.plan.web.dto.WorkBlockResponse;
import com.example.starter.plan.web.dto.WorkBlockView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 区段维护封锁 API：登记封锁（同事务撤销/改签全部相交计划后生效）、
 * 封锁生效后的批量原子改签、封锁列表与详情（受影响计划及处理结果）查询。
 */
@Validated
@RestController
@RequestMapping("/api/v1/work-blocks")
public class WorkBlockController {

    private final WorkBlockService service;

    public WorkBlockController(WorkBlockService service) {
        this.service = service;
    }

    /**
     * 登记封锁：必须在同一次操作中撤销或改签全部相交已发布计划，完成后封锁才生效。
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public WorkBlockResponse register(@Valid @RequestBody RegisterWorkBlockRequest request) {
        return service.registerBlock(request);
    }

    /**
     * 封锁生效后的批量改签：任一项仍冲突则全部计划与封锁状态保持原样。
     */
    @PostMapping("/batch-reschedule")
    public BatchRescheduleResponse batchReschedule(
            @Valid @RequestBody BatchRescheduleRequest request) {
        return service.batchReschedule(request);
    }

    /**
     * 封锁详情：封锁窗口、受影响计划及每个计划的处理结果。
     */
    @GetMapping("/{blockKey}")
    public WorkBlockDetailResponse getBlock(@PathVariable String blockKey) {
        return service.getBlock(blockKey);
    }

    /**
     * 按运营日与区段列出已生效封锁。
     */
    @GetMapping
    public List<WorkBlockView> listBlocks(
            @RequestParam @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam @NotBlank String sectionId) {
        return service.listBlocks(date, sectionId);
    }
}
