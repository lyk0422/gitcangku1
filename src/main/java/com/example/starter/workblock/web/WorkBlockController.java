package com.example.starter.workblock.web;

import com.example.starter.workblock.service.WorkBlockService;
import com.example.starter.workblock.web.dto.AffectedPlanView;
import com.example.starter.workblock.web.dto.CancelWorkBlockRequest;
import com.example.starter.workblock.web.dto.CreateWorkBlockRequest;
import com.example.starter.workblock.web.dto.UpdateWorkBlockRequest;
import com.example.starter.workblock.web.dto.WorkBlockCancellationView;
import com.example.starter.workblock.web.dto.WorkBlockView;
import jakarta.validation.Valid;
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
 * 铁路施工占用窗口 API：创建/修改/取消、窗口查询、受影响计划与取消记录查询。
 */
@Validated
@RestController
@RequestMapping("/api/v1/work-blocks")
public class WorkBlockController {

    private final WorkBlockService service;

    public WorkBlockController(WorkBlockService service) {
        this.service = service;
    }

    /** 创建施工单（区段必须存在、起点早于终点、重叠返回 409）。 */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public WorkBlockView create(@Valid @RequestBody CreateWorkBlockRequest request) {
        return service.create(request);
    }

    /** 修改施工单（版本乐观校验，重校验全部已发布计划，冲突 422 不部分生效）。 */
    @PutMapping("/{workKey}")
    public WorkBlockView update(@PathVariable String workKey,
                                @Valid @RequestBody UpdateWorkBlockRequest request) {
        return service.update(workKey, request);
    }

    /** 取消未开始施工单（已开始不可取消，立即释放并保留不可变取消记录）。 */
    @PostMapping("/{workKey}/cancel")
    public WorkBlockView cancel(@PathVariable String workKey,
                                @Valid @RequestBody CancelWorkBlockRequest request) {
        return service.cancel(workKey, request);
    }

    /** 查询单个施工窗口。 */
    @GetMapping("/{workKey}")
    public WorkBlockView get(@PathVariable String workKey) {
        return service.get(workKey);
    }

    /** 查询施工窗口列表（includeCancelled=true 时含已取消）。 */
    @GetMapping
    public List<WorkBlockView> list(@RequestParam(defaultValue = "false") boolean includeCancelled) {
        return service.list(includeCancelled);
    }

    /** 查询指定施工窗口当前相交的已发布计划。 */
    @GetMapping("/{workKey}/affected-plans")
    public List<AffectedPlanView> affectedPlans(@PathVariable String workKey) {
        return service.getAffectedPlans(workKey);
    }

    /** 查询取消记录（workKey 可选）。 */
    @GetMapping("/cancellations")
    public List<WorkBlockCancellationView> cancellations(
            @RequestParam(required = false) String workKey) {
        return service.getCancellations(workKey);
    }
}
