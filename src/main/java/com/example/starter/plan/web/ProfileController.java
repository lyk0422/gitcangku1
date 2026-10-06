package com.example.starter.plan.web;

import com.example.starter.plan.service.AxleEnvelopeService;
import com.example.starter.plan.web.dto.SectionLimitProfileCreateRequest;
import com.example.starter.plan.web.dto.SectionLimitProfileResponse;
import com.example.starter.plan.web.dto.SectionLimitProfileUpdateRequest;
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
 * 列车有序轴重档案与区段轴重限值档案 API。
 * 写操作携带全局 requestId（幂等键）与非空 operator（操作者）。
 */
@Validated
@RestController
@RequestMapping("/api/v1")
public class ProfileController {

    private final AxleEnvelopeService service;

    public ProfileController(AxleEnvelopeService service) {
        this.service = service;
    }

    /**
     * 创建列车有序轴重档案（版本 1）。
     */
    @PostMapping("/train-axle-profiles")
    @ResponseStatus(HttpStatus.CREATED)
    public TrainAxleProfileResponse createTrainProfile(
            @Valid @RequestBody TrainAxleProfileCreateRequest request) {
        return service.createTrainProfile(request);
    }

    /**
     * 更新列车有序轴重档案（expectedVersion 乐观校验，成功版本加一）。
     */
    @PutMapping("/train-axle-profiles/{trainNo}")
    public TrainAxleProfileResponse updateTrainProfile(
            @PathVariable String trainNo,
            @Valid @RequestBody TrainAxleProfileUpdateRequest request) {
        return service.updateTrainProfile(trainNo, request);
    }

    /**
     * 查询列车有序轴重档案。
     */
    @GetMapping("/train-axle-profiles/{trainNo}")
    public TrainAxleProfileResponse getTrainProfile(@PathVariable String trainNo) {
        return service.getTrainProfile(trainNo);
    }

    /**
     * 创建区段轴重限值档案（版本 1）。
     */
    @PostMapping("/section-limit-profiles")
    @ResponseStatus(HttpStatus.CREATED)
    public SectionLimitProfileResponse createSectionProfile(
            @Valid @RequestBody SectionLimitProfileCreateRequest request) {
        return service.createSectionProfile(request);
    }

    /**
     * 更新区段轴重限值档案（expectedVersion 乐观校验，成功版本加一）。
     */
    @PutMapping("/section-limit-profiles/{sectionId}")
    public SectionLimitProfileResponse updateSectionProfile(
            @PathVariable String sectionId,
            @Valid @RequestBody SectionLimitProfileUpdateRequest request) {
        return service.updateSectionProfile(sectionId, request);
    }

    /**
     * 查询区段轴重限值档案。
     */
    @GetMapping("/section-limit-profiles/{sectionId}")
    public SectionLimitProfileResponse getSectionProfile(@PathVariable String sectionId) {
        return service.getSectionProfile(sectionId);
    }
}
