package com.example.starter.firmware.api;

import com.example.starter.firmware.service.ProfileLaunchService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 画像投放：发布人创建投放与查看投放单（含失败率审计），任务回执。
 */
@RestController
public class ProfileLaunchController {

    private final ProfileLaunchService launchService;

    public ProfileLaunchController(ProfileLaunchService launchService) {
        this.launchService = launchService;
    }

    @PostMapping("/api/hw/launches")
    public LaunchView launch(@Valid @RequestBody CreateLaunchRequest request) {
        return launchService.launch(request);
    }

    @GetMapping("/api/hw/launches/{launchId}")
    public LaunchView get(@PathVariable long launchId) {
        return launchService.getLaunch(launchId);
    }

    @PostMapping("/api/hw/tasks/{taskId}/receipt")
    public LaunchTaskView receipt(@PathVariable long taskId, @Valid @RequestBody ReceiptRequest request) {
        return launchService.receipt(taskId, request);
    }
}
