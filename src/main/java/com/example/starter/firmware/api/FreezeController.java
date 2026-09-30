package com.example.starter.firmware.api;

import com.example.starter.firmware.service.FreezeService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 目标固件版本正式发布冻结：创建冻结、申请恢复、异于冻结创建人的操作人审批恢复，
 * 以及当前生效冻结与冻结区间/审计流水的只读查询。
 */
@RestController
@RequestMapping("/api/freezes")
public class FreezeController {

    private final FreezeService freezeService;

    public FreezeController(FreezeService freezeService) {
        this.freezeService = freezeService;
    }

    @PostMapping
    public FreezeView freeze(@Valid @RequestBody FreezeVersionRequest request) {
        return freezeService.freeze(request);
    }

    @PostMapping("/{freezeId}/resume-requests")
    public FreezeView requestResume(@PathVariable long freezeId,
                                    @Valid @RequestBody ResumeFreezeRequest request) {
        return freezeService.requestResume(freezeId, request);
    }

    @PostMapping("/{freezeId}/approvals")
    public FreezeView approveResume(@PathVariable long freezeId,
                                    @Valid @RequestBody ApproveFreezeResumeRequest request) {
        return freezeService.approveResume(freezeId, request);
    }

    @GetMapping("/active")
    public FreezeView active(@RequestParam String version) {
        return freezeService.active(version);
    }

    @GetMapping
    public FreezeHistoryResponse history(@RequestParam String version) {
        return freezeService.history(version);
    }
}
