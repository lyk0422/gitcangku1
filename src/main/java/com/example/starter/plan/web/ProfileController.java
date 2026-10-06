package com.example.starter.plan.web;

import com.example.starter.plan.service.ProfileService;
import com.example.starter.plan.web.dto.SectionProfileRequest;
import com.example.starter.plan.web.dto.SectionProfileResponse;
import com.example.starter.plan.web.dto.SectionProfileUpdateRequest;
import com.example.starter.plan.web.dto.TrainProfileRequest;
import com.example.starter.plan.web.dto.TrainProfileResponse;
import com.example.starter.plan.web.dto.TrainProfileUpdateRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 列车/区段制动档案 API：创建（版本 1）、带 expectedVersion 的修改与查询。
 * 新增写入用 requestId 幂等；修改若使任一已发布占用制动包络越限则 409。
 */
@RestController
@RequestMapping("/api/v1")
public class ProfileController {

    private final ProfileService service;

    public ProfileController(ProfileService service) {
        this.service = service;
    }

    /**
     * 创建列车制动档案。
     */
    @PostMapping("/train-profiles")
    @ResponseStatus(HttpStatus.CREATED)
    public TrainProfileResponse createTrainProfile(@Valid @RequestBody TrainProfileRequest request) {
        return service.createTrainProfile(request);
    }

    /**
     * 修改列车制动档案（expectedVersion 乐观校验，成功版本加一）。
     */
    @PutMapping("/train-profiles/{trainNo}")
    public TrainProfileResponse updateTrainProfile(@PathVariable String trainNo,
                                                   @Valid @RequestBody TrainProfileUpdateRequest request) {
        return service.updateTrainProfile(trainNo, request);
    }

    /**
     * 查询列车制动档案。
     */
    @GetMapping("/train-profiles/{trainNo}")
    public TrainProfileResponse getTrainProfile(@PathVariable String trainNo) {
        return service.getTrainProfile(trainNo);
    }

    /**
     * 创建区段防护档案。
     */
    @PostMapping("/section-profiles")
    @ResponseStatus(HttpStatus.CREATED)
    public SectionProfileResponse createSectionProfile(
            @Valid @RequestBody SectionProfileRequest request) {
        return service.createSectionProfile(request);
    }

    /**
     * 修改区段防护档案（expectedVersion 乐观校验，成功版本加一）。
     */
    @PutMapping("/section-profiles/{sectionId}")
    public SectionProfileResponse updateSectionProfile(
            @PathVariable String sectionId,
            @Valid @RequestBody SectionProfileUpdateRequest request) {
        return service.updateSectionProfile(sectionId, request);
    }

    /**
     * 查询区段防护档案。
     */
    @GetMapping("/section-profiles/{sectionId}")
    public SectionProfileResponse getSectionProfile(@PathVariable String sectionId) {
        return service.getSectionProfile(sectionId);
    }
}
