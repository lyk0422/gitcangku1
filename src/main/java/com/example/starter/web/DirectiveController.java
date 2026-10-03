package com.example.starter.web;

import com.example.starter.service.DirectiveService;
import com.example.starter.web.dto.Requests.ConfirmRequest;
import com.example.starter.web.dto.Requests.CreateDirectiveRequest;
import com.example.starter.web.dto.Requests.ReadingRequest;
import com.example.starter.web.dto.Requests.ReconcileRequest;
import com.example.starter.web.dto.Responses.DifferenceResponse;
import com.example.starter.web.dto.Responses.DirectiveResponse;
import com.example.starter.web.dto.Responses.ReadingResponse;
import com.example.starter.web.dto.Responses.ReconciliationResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 水电站限发调度接口：指令、确认/撤销、采样上报、对账与差额链查询。
 */
@RestController
@RequestMapping("/api")
public class DirectiveController {

    private final DirectiveService service;

    public DirectiveController(DirectiveService service) {
        this.service = service;
    }

    /** 创建限发指令。 */
    @PostMapping("/directives")
    @ResponseStatus(HttpStatus.CREATED)
    public DirectiveResponse create(@Valid @RequestBody CreateDirectiveRequest request) {
        return service.create(request);
    }

    /** 查询指令详情（含状态与版本）。 */
    @GetMapping("/directives/{id}")
    public DirectiveResponse get(@PathVariable long id) {
        return service.getDirective(id);
    }

    /** 确认指令（幂等）。 */
    @PostMapping("/directives/{id}/confirm")
    public DirectiveResponse confirm(@PathVariable long id, @Valid @RequestBody ConfirmRequest request) {
        return service.confirm(id, request);
    }

    /** 撤销指令。 */
    @PostMapping("/directives/{id}/cancel")
    public DirectiveResponse cancel(@PathVariable long id) {
        return service.cancel(id);
    }

    /** 上报 15 分钟平均功率采样；同刻度重复上报为迟到更正。 */
    @PostMapping("/directives/{id}/readings")
    @ResponseStatus(HttpStatus.CREATED)
    public ReadingResponse reportReading(@PathVariable long id, @Valid @RequestBody ReadingRequest request) {
        return service.reportReading(id, request);
    }

    /** 查询每刻度最新采样。 */
    @GetMapping("/directives/{id}/readings")
    public List<ReadingResponse> listReadings(@PathVariable long id) {
        return service.listReadings(id);
    }

    /** 对账（幂等）；校验失败整单拒绝并返回问题刻度。 */
    @PostMapping("/directives/{id}/reconcile")
    public ReconciliationResponse reconcile(@PathVariable long id, @Valid @RequestBody ReconcileRequest request) {
        return service.reconcile(id, request);
    }

    /** 查询全部对账版本（含固化采样）。 */
    @GetMapping("/directives/{id}/reconciliations")
    public List<ReconciliationResponse> listReconciliations(@PathVariable long id) {
        return service.listReconciliations(id);
    }

    /** 查询差额链。 */
    @GetMapping("/directives/{id}/differences")
    public List<DifferenceResponse> listDifferences(@PathVariable long id) {
        return service.listDifferences(id);
    }
}