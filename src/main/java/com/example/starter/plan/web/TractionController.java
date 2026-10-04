package com.example.starter.plan.web;

import com.example.starter.plan.service.TractionService;
import com.example.starter.plan.web.dto.CreateSectionProfileRequest;
import com.example.starter.plan.web.dto.SectionProfileResponse;
import com.example.starter.plan.web.dto.TrainTractionResponse;
import com.example.starter.plan.web.dto.UpdateSectionProfileRequest;
import com.example.starter.plan.web.dto.UpsertTrainTractionRequest;
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
 * 牵引档案 API：列车牵引方式 upsert、区段档案创建/变更与查询。
 */
@RestController
@RequestMapping("/api/v1")
public class TractionController {

    private final TractionService service;

    public TractionController(TractionService service) {
        this.service = service;
    }

    /**
     * 新增或变更列车牵引档案；若使任一已发布计划非法则 409。
     */
    @PutMapping("/trains/{trainNo}/traction")
    public TrainTractionResponse upsertTrainTraction(@PathVariable String trainNo,
                                                     @Valid @RequestBody UpsertTrainTractionRequest request) {
        return service.upsertTrainTraction(trainNo, request);
    }

    /**
     * 查询列车牵引档案。
     */
    @GetMapping("/trains/{trainNo}/traction")
    public TrainTractionResponse getTrainTraction(@PathVariable String trainNo) {
        return service.getTrainTraction(trainNo);
    }

    /**
     * 创建区段牵引档案（初始版本 1）。
     */
    @PostMapping("/section-profiles")
    @ResponseStatus(HttpStatus.CREATED)
    public SectionProfileResponse createSectionProfile(
            @Valid @RequestBody CreateSectionProfileRequest request) {
        return service.createSectionProfile(request);
    }

    /**
     * 变更区段牵引档案（expectedVersion 乐观校验，成功版本加一）；
     * 若使任一已发布计划非法则 409，须先取消相关计划。
     */
    @PutMapping("/section-profiles/{sectionId}")
    public SectionProfileResponse updateSectionProfile(
            @PathVariable String sectionId,
            @Valid @RequestBody UpdateSectionProfileRequest request) {
        return service.updateSectionProfile(sectionId, request);
    }

    /**
     * 查询区段牵引档案。
     */
    @GetMapping("/section-profiles/{sectionId}")
    public SectionProfileResponse getSectionProfile(@PathVariable String sectionId) {
        return service.getSectionProfile(sectionId);
    }
}