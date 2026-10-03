package com.example.starter.curtailment.api;

import com.example.starter.curtailment.service.CurtailmentService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 限发 REST 接口：指令创建/确认/撤销、采样上报与更正、对账、详情查询。
 */
@RestController
@RequestMapping("/api/v1/instructions")
public class CurtailmentController {

    private final CurtailmentService service;

    public CurtailmentController(CurtailmentService service) {
        this.service = service;
    }

    /** 创建限发指令。 */
    @PostMapping
    public ResponseEntity<ApiDtos.InstructionView> create(@Valid @RequestBody ApiDtos.CreateInstructionRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createInstruction(request));
    }

    /** 机组确认（幂等）。 */
    @PostMapping("/{id}/confirm")
    public ApiDtos.InstructionView confirm(@PathVariable long id, @Valid @RequestBody ApiDtos.ConfirmRequest request) {
        return service.confirm(id, request);
    }

    /** 撤销指令（幂等）。 */
    @PostMapping("/{id}/revoke")
    public ApiDtos.InstructionView revoke(@PathVariable long id, @Valid @RequestBody ApiDtos.RevokeRequest request) {
        return service.revoke(id, request);
    }

    /** 上报 15 分钟平均功率；同刻度再次上报（不同 requestId）即迟到更正（幂等）。 */
    @PostMapping("/{id}/samples")
    public ResponseEntity<ApiDtos.SampleView> reportSample(@PathVariable long id,
                                                           @Valid @RequestBody ApiDtos.SampleRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.reportSample(id, request));
    }

    /** 对账：固化指令版本、四个采样刻度与累计电量（幂等）。 */
    @PostMapping("/{id}/reconciliations")
    public ResponseEntity<ApiDtos.ReconciliationView> reconcile(@PathVariable long id,
                                                                @Valid @RequestBody ApiDtos.ReconcileRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.reconcile(id, request));
    }

    /** 查询指令详情：版本、采样刻度、对账单与差额链。 */
    @GetMapping("/{id}")
    public ApiDtos.InstructionDetailView detail(@PathVariable long id) {
        return service.getDetail(id);
    }
}