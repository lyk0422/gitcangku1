package com.example.starter.plan.web;

import com.example.starter.plan.service.ActualMovementService;
import com.example.starter.plan.web.dto.ActualActionRequest;
import com.example.starter.plan.web.dto.ActualLockView;
import com.example.starter.plan.web.dto.ActualMovementResponse;
import com.example.starter.plan.web.dto.PlanActualResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 实际进出确认 API：进入/退出确认、计划实际状态与区段实际锁查询。
 * 确认时刻由服务端时钟产生；查询不触发自动退出。
 */
@Validated
@RestController
@RequestMapping("/api/v1")
public class ActualController {

    private final ActualMovementService service;

    public ActualController(ActualMovementService service) {
        this.service = service;
    }

    /**
     * 确认进入：服务端时刻须在原左闭右开时隙内，同区段至多一条 INSIDE。
     */
    @PostMapping("/plans/{scheduleKey}/occupancies/{seq}/enter")
    public ActualMovementResponse enter(@PathVariable String scheduleKey, @PathVariable int seq,
                                        @Valid @RequestBody ActualActionRequest request) {
        return service.enter(scheduleKey, seq, request);
    }

    /**
     * 确认退出：必须对应当前 INSIDE，退出轨迹与释放实际锁同事务。
     */
    @PostMapping("/plans/{scheduleKey}/occupancies/{seq}/exit")
    public ActualMovementResponse exit(@PathVariable String scheduleKey, @PathVariable int seq,
                                       @Valid @RequestBody ActualActionRequest request) {
        return service.exit(scheduleKey, seq, request);
    }

    /**
     * 计划实际状态：每条占用的计划时隙与实际轨迹时刻分列。
     */
    @GetMapping("/plans/{scheduleKey}/actual")
    public PlanActualResponse getPlanActual(@PathVariable String scheduleKey) {
        return service.getPlanActual(scheduleKey);
    }

    /**
     * 区段当前实际锁（INSIDE 占用）查询。
     */
    @GetMapping("/actual-locks")
    public List<ActualLockView> getActualLocks(@RequestParam @NotBlank String sectionId) {
        return service.getActualLocks(sectionId);
    }
}
