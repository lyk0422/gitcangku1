package com.example.starter.plan.web;

import com.example.starter.plan.service.ActualMovementService;
import com.example.starter.plan.web.dto.ActualActionRequest;
import com.example.starter.plan.web.dto.ActualLockView;
import com.example.starter.plan.web.dto.ActualMovementResponse;
import com.example.starter.plan.web.dto.ActualOccupancyView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 实际进出确认 API：进入/驶出、占用实际状态与区段实际锁查询。
 * 查询为只读，不自动驶出过期 INSIDE。
 */
@Validated
@RestController
@RequestMapping("/api/v1")
public class ActualMovementController {

    private final ActualMovementService service;

    public ActualMovementController(ActualMovementService service) {
        this.service = service;
    }

    /**
     * 确认进入：服务端 UTC 时刻须落在原计划左闭右开时隙内，同区段至多一个 INSIDE。
     */
    @PostMapping("/plans/{scheduleKey}/occupancies/{seq}/enter")
    public ActualMovementResponse enter(@PathVariable String scheduleKey,
                                        @PathVariable int seq,
                                        @Valid @RequestBody ActualActionRequest request) {
        return service.enter(scheduleKey, seq, request);
    }

    /**
     * 确认驶出：必须对应当前 INSIDE，记录服务端时刻与原计划版本。
     */
    @PostMapping("/plans/{scheduleKey}/occupancies/{seq}/exit")
    public ActualMovementResponse exit(@PathVariable String scheduleKey,
                                       @PathVariable int seq,
                                       @Valid @RequestBody ActualActionRequest request) {
        return service.exit(scheduleKey, seq, request);
    }

    /**
     * 查询计划全部占用的实际状态（计划时隙与实际时刻分列）。
     */
    @GetMapping("/plans/{scheduleKey}/actual-occupancies")
    public List<ActualOccupancyView> getActualOccupancies(@PathVariable String scheduleKey) {
        return service.getActualOccupancies(scheduleKey);
    }

    /**
     * 查询指定运营日与区段上当前 INSIDE 的实际锁。
     */
    @GetMapping("/actual-locks")
    public List<ActualLockView> getActualLocks(
            @RequestParam @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam @NotBlank String sectionId) {
        return service.getActualLocks(date, sectionId);
    }
}
