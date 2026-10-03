package com.example.starter.web;

import com.example.starter.service.TagService;
import com.example.starter.service.TimelineService;
import com.example.starter.service.TransferService;
import com.example.starter.web.dto.ApiDtos.AnimalTimelineResponse;
import com.example.starter.web.dto.ApiDtos.AttachRequest;
import com.example.starter.web.dto.ApiDtos.AttachmentResponse;
import com.example.starter.web.dto.ApiDtos.DetachRequest;
import com.example.starter.web.dto.ApiDtos.RegisterTagRequest;
import com.example.starter.web.dto.ApiDtos.TagResponse;
import com.example.starter.web.dto.ApiDtos.TagTimelineResponse;
import com.example.starter.web.dto.ApiDtos.TransferConfirmRequest;
import com.example.starter.web.dto.ApiDtos.TransferInitRequest;
import com.example.starter.web.dto.ApiDtos.TransferResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 野外标签登记、附着、摘除、交接与时间线查询入口。
 */
@RestController
@RequestMapping("/api")
public class WildlifeController {

    private final TagService tagService;
    private final TransferService transferService;
    private final TimelineService timelineService;

    public WildlifeController(TagService tagService, TransferService transferService,
            TimelineService timelineService) {
        this.tagService = tagService;
        this.transferService = transferService;
        this.timelineService = timelineService;
    }

    @PostMapping("/tags")
    @ResponseStatus(HttpStatus.CREATED)
    public TagResponse registerTag(@Valid @RequestBody RegisterTagRequest request) {
        return tagService.registerTag(request);
    }

    @GetMapping("/tags/{tagCode}")
    public TagResponse getTag(@PathVariable String tagCode) {
        return tagService.getTag(tagCode);
    }

    @PostMapping("/attachments")
    @ResponseStatus(HttpStatus.CREATED)
    public AttachmentResponse attach(@Valid @RequestBody AttachRequest request) {
        return tagService.attach(request);
    }

    @PostMapping("/attachments/detach")
    public AttachmentResponse detach(@Valid @RequestBody DetachRequest request) {
        return tagService.detach(request);
    }

    @PostMapping("/transfers")
    @ResponseStatus(HttpStatus.CREATED)
    public TransferResponse initiateTransfer(@Valid @RequestBody TransferInitRequest request) {
        return transferService.initiate(request);
    }

    @PostMapping("/transfers/{id}/confirm")
    public TransferResponse confirmTransfer(@PathVariable long id,
            @Valid @RequestBody TransferConfirmRequest request) {
        return transferService.confirm(id, request);
    }

    @PostMapping("/transfers/{id}/cancel")
    public TransferResponse cancelTransfer(@PathVariable long id) {
        return transferService.cancel(id);
    }

    @GetMapping("/tags/{tagCode}/timeline")
    public TagTimelineResponse tagTimeline(@PathVariable String tagCode) {
        return timelineService.tagTimeline(tagCode);
    }

    @GetMapping("/animals/{animalCode}/timeline")
    public AnimalTimelineResponse animalTimeline(@PathVariable String animalCode) {
        return timelineService.animalTimeline(animalCode);
    }
}