package com.example.starter.firmware.api;

import com.example.starter.firmware.service.CohortService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 设备代次下发指令回执。新代次回执只能结算一次；迁移提交后到达的旧代次回执仅存档 LATE。
 */
@RestController
@RequestMapping("/api/commands")
public class CommandController {

    private final CohortService cohortService;

    public CommandController(CohortService cohortService) {
        this.cohortService = cohortService;
    }

    @PostMapping("/{commandId}/receipt")
    public CommandView receipt(@PathVariable long commandId, @Valid @RequestBody CommandReceiptRequest request) {
        return cohortService.receipt(commandId, request);
    }
}
