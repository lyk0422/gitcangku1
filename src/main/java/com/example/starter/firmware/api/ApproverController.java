package com.example.starter.firmware.api;

import com.example.starter.firmware.service.ApproverService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 冻结紧急例外确认人登记与查询。
 */
@RestController
@RequestMapping("/api/freeze-approvers")
public class ApproverController {

    private final ApproverService approverService;

    public ApproverController(ApproverService approverService) {
        this.approverService = approverService;
    }

    @PostMapping
    public ApproverView register(@Valid @RequestBody RegisterApproverRequest request) {
        return new ApproverView(approverService.register(request));
    }

    @GetMapping
    public List<String> list() {
        return approverService.list();
    }

    /**
     * 确认人视图。
     */
    public record ApproverView(String approverId) {
    }
}
