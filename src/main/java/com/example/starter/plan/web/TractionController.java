package com.example.starter.plan.web;

import com.example.starter.plan.service.TractionService;
import com.example.starter.plan.web.dto.SectionProfileRequest;
import com.example.starter.plan.web.dto.SectionProfileResponse;
import com.example.starter.plan.web.dto.TractionSnapshotResponse;
import com.example.starter.plan.web.dto.TrainProfileRequest;
import com.example.starter.plan.web.dto.TrainProfileResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 牵引档案 API：列车牵引方式与区段供电/柴油许可的新增、变更（expectedVersion 乐观校验）、
 * 查询，以及计划发布时刻冻结牵引快照查询。
 */
@RestController
@RequestMapping("/api/v1")
public class TractionController {

    private final TractionService service;

    public TractionController(TractionService service) {
        this.service = service;
    }

    /**
     * 新增或变更列车牵引档案（expectedVersion 为 0 表示新增，成功版本加一）。
     */
    @PutMapping("/train-profiles/{trainNo}")
    public TrainProfileResponse upsertTrainProfile(@PathVariable String trainNo,
                                                   @Valid @RequestBody TrainProfileRequest request) {
        return service.upsertTrainProfile(trainNo, request);
    }

    /**
     * 查询列车牵引档案，不存在返回 404。
     */
    @GetMapping("/train-profiles/{trainNo}")
    public TrainProfileResponse getTrainProfile(@PathVariable String trainNo) {
        return service.findTrainProfile(trainNo)
                .orElseThrow(() -> new ApiException(org.springframework.http.HttpStatus.NOT_FOUND,
                        "PROFILE_NOT_FOUND", "列车牵引档案不存在: " + trainNo));
    }

    /**
     * 新增或变更区段牵引档案（expectedVersion 为 0 表示新增，成功版本加一）。
     */
    @PutMapping("/section-profiles/{sectionId}")
    public SectionProfileResponse upsertSectionProfile(
            @PathVariable String sectionId,
            @Valid @RequestBody SectionProfileRequest request) {
        return service.upsertSectionProfile(sectionId, request);
    }

    /**
     * 查询区段牵引档案，不存在返回 404。
     */
    @GetMapping("/section-profiles/{sectionId}")
    public SectionProfileResponse getSectionProfile(@PathVariable String sectionId) {
        return service.findSectionProfile(sectionId)
                .orElseThrow(() -> new ApiException(org.springframework.http.HttpStatus.NOT_FOUND,
                        "PROFILE_NOT_FOUND", "区段牵引档案不存在: " + sectionId));
    }

    /**
     * 查询计划发布时刻冻结的牵引快照（牵引方式与每段档案值、版本）。
     */
    @GetMapping("/plans/{scheduleKey}/traction-snapshot")
    public TractionSnapshotResponse getTractionSnapshot(@PathVariable String scheduleKey) {
        return service.getSnapshot(scheduleKey);
    }
}