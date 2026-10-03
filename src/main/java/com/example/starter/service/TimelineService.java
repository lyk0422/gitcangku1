package com.example.starter.service;

import java.util.List;

import com.example.starter.domain.TagRow;
import com.example.starter.repo.AnimalRepository;
import com.example.starter.repo.AttachmentRepository;
import com.example.starter.repo.RejectionRepository;
import com.example.starter.repo.TransferRepository;
import com.example.starter.web.dto.ApiDtos.AnimalTimelineResponse;
import com.example.starter.web.dto.ApiDtos.RejectionResponse;
import com.example.starter.web.dto.ApiDtos.TagTimelineResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 时间线查询：按标签/动物汇总附着区间、交接确认时刻与拒绝原因。
 */
@Service
public class TimelineService {

    private final TagService tagService;
    private final AttachmentRepository attachmentRepository;
    private final TransferRepository transferRepository;
    private final RejectionRepository rejectionRepository;
    private final AnimalRepository animalRepository;

    public TimelineService(TagService tagService, AttachmentRepository attachmentRepository,
            TransferRepository transferRepository, RejectionRepository rejectionRepository,
            AnimalRepository animalRepository) {
        this.tagService = tagService;
        this.attachmentRepository = attachmentRepository;
        this.transferRepository = transferRepository;
        this.rejectionRepository = rejectionRepository;
        this.animalRepository = animalRepository;
    }

    @Transactional(readOnly = true)
    public TagTimelineResponse tagTimeline(String tagCode) {
        TagRow tag = tagService.requireTag(tagCode);
        var attachments = attachmentRepository.findByTag(tagCode).stream()
                .map(TagService::toResponse)
                .toList();
        var transfers = transferRepository.findByTag(tagCode).stream()
                .map(TransferService::toResponse)
                .toList();
        List<RejectionResponse> rejections = rejectionRepository.findByTag(tagCode).stream()
                .map(r -> new RejectionResponse(r.operation(), r.reason(), r.occurredAt()))
                .toList();
        return new TagTimelineResponse(tag.tagCode(), tag.holderStationCode(), tag.status().name(),
                attachments, transfers, rejections);
    }

    @Transactional(readOnly = true)
    public AnimalTimelineResponse animalTimeline(String animalCode) {
        if (!animalRepository.exists(animalCode)) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "ANIMAL_NOT_FOUND",
                    "动物不存在: " + animalCode, null, animalCode, null);
        }
        var attachments = attachmentRepository.findByAnimal(animalCode).stream()
                .map(TagService::toResponse)
                .toList();
        List<RejectionResponse> rejections = rejectionRepository.findByAnimal(animalCode).stream()
                .map(r -> new RejectionResponse(r.operation(), r.reason(), r.occurredAt()))
                .toList();
        return new AnimalTimelineResponse(animalCode, attachments, rejections);
    }
}