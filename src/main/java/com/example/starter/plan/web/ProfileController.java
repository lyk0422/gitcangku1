package com.example.starter.plan.web;

import com.example.starter.plan.service.ProfileService;
import com.example.starter.plan.web.dto.SectionProfileRequest;
import com.example.starter.plan.web.dto.SectionProfileResponse;
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
 * 列车应急制动档案与区段防护档案 API：创建/修改（版本从 1 递增，修改带 expectedVersion）
 * 与查询。写入以 requestId 幂等，身份含操作、操作者、资源及全部参数和期望版本。
 */
@RestController
@RequestMapping("/api/v1")
public class ProfileController {

    private final ProfileService service;

    public ProfileController(ProfileService service) {
        this.service = service;
    }

    /**
     * 创建（expectedVersion=0）或修改（expectedVersion 匹配当前版本）列车档案。
     */
    @PutMapping("/train-profiles/{trainNo}")
    public TrainProfileResponse upsertTrainProfile(@PathVariable String trainNo,
                                                   @Valid @RequestBody TrainProfileRequest request) {
        return service.upsertTrainProfile(trainNo, request);
    }

    /**
     * 创建（expectedVersion=0）或修改（expectedVersion 匹配当前版本）区段档案。
     */
    @PutMapping("/section-profiles/{sectionId}")
    public SectionProfileResponse upsertSectionProfile(
            @PathVariable String sectionId,
            @Valid @RequestBody SectionProfileRequest request) {
        return service.upsertSectionProfile(sectionId, request);
    }

    /**
     * 查询列车档案，不存在返回 404。
     */
    @GetMapping("/train-profiles/{trainNo}")
    public TrainProfileResponse getTrainProfile(@PathVariable String trainNo) {
        return service.getTrainProfile(trainNo);
    }

    /**
     * 查询区段档案，不存在返回 404。
     */
    @GetMapping("/section-profiles/{sectionId}")
    public SectionProfileResponse getSectionProfile(@PathVariable String sectionId) {
        return service.getSectionProfile(sectionId);
    }
}
