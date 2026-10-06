package com.example.starter.plan.web;

import com.example.starter.plan.service.AxleProfileService;
import com.example.starter.plan.web.dto.SectionAxleLimitCreateRequest;
import com.example.starter.plan.web.dto.SectionAxleLimitResponse;
import com.example.starter.plan.web.dto.SectionAxleLimitUpdateRequest;
import com.example.starter.plan.web.dto.TrainAxleProfileCreateRequest;
import com.example.starter.plan.web.dto.TrainAxleProfileResponse;
import com.example.starter.plan.web.dto.TrainAxleProfileUpdateRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 列车轴重档案与区段轴限档案 API：登记、整体替换（expectedVersion 乐观校验）与查询。
 * 所有写操作携带全局 requestId 与非空操作者，按 (操作类型, requestId) 幂等。
 */
@Validated
@RestController
@RequestMapping("/api/v1")
public class AxleProfileController {

    private final AxleProfileService service;

    public AxleProfileController(AxleProfileService service) {
        this.service = service;
    }

    /**
     * 登记列车轴重档案（版本 1，2 至 16 轴，每轴正整数千克）。
     */
    @PostMapping("/train-axle-profiles/{trainNo}")
    @ResponseStatus(HttpStatus.CREATED)
    public TrainAxleProfileResponse createTrainProfile(
            @PathVariable String trainNo,
            @Valid @RequestBody TrainAxleProfileCreateRequest request) {
        return service.createTrainProfile(trainNo, request);
    }

    /**
     * 整体替换列车轴序列（版本加一）；若使已发布计划非法则 409，版本不变。
     */
    @PutMapping("/train-axle-profiles/{trainNo}")
    public TrainAxleProfileResponse updateTrainProfile(
            @PathVariable String trainNo,
            @Valid @RequestBody TrainAxleProfileUpdateRequest request) {
        return service.updateTrainProfile(trainNo, request);
    }

    /**
     * 查询列车轴重档案。
     */
    @GetMapping("/train-axle-profiles/{trainNo}")
    public TrainAxleProfileResponse getTrainProfile(@PathVariable String trainNo) {
        return service.getTrainProfile(trainNo);
    }

    /**
     * 登记区段轴限档案（版本 1，单轴上限与连续 3 轴总重上限，单位千克）。
     */
    @PostMapping("/section-axle-limits/{sectionId}")
    @ResponseStatus(HttpStatus.CREATED)
    public SectionAxleLimitResponse createSectionLimit(
            @PathVariable String sectionId,
            @Valid @RequestBody SectionAxleLimitCreateRequest request) {
        return service.createSectionLimit(sectionId, request);
    }

    /**
     * 修改区段轴限（版本加一）；若使已发布计划非法则 409，版本不变。
     */
    @PutMapping("/section-axle-limits/{sectionId}")
    public SectionAxleLimitResponse updateSectionLimit(
            @PathVariable String sectionId,
            @Valid @RequestBody SectionAxleLimitUpdateRequest request) {
        return service.updateSectionLimit(sectionId, request);
    }

    /**
     * 查询区段轴限档案。
     */
    @GetMapping("/section-axle-limits/{sectionId}")
    public SectionAxleLimitResponse getSectionLimit(@PathVariable String sectionId) {
        return service.getSectionLimit(sectionId);
    }
}
