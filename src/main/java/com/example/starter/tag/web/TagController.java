package com.example.starter.tag.web;

import com.example.starter.tag.service.TagService;
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
 * 标签登记、附着、摘除、站点交接与历史查询的 HTTP 入口。
 */
@RestController
@RequestMapping("/api/tags")
public class TagController {

    private final TagService tagService;

    public TagController(TagService tagService) {
        this.tagService = tagService;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public void register(@Valid @RequestBody TagRegisterRequest request) {
        tagService.registerTag(request);
    }

    @PostMapping("/attach")
    @ResponseStatus(HttpStatus.CREATED)
    public AttachResponse attach(@Valid @RequestBody AttachRequest request) {
        return tagService.attach(request);
    }

    @PostMapping("/detach")
    @ResponseStatus(HttpStatus.OK)
    public void detach(@Valid @RequestBody DetachRequest request) {
        tagService.detach(request);
    }

    @PostMapping("/handoffs")
    @ResponseStatus(HttpStatus.CREATED)
    public HandoffResponse initiateHandoff(@Valid @RequestBody HandoffInitiateRequest request) {
        return tagService.initiateHandoff(request);
    }

    @PostMapping("/handoffs/confirm")
    public HandoffResponse confirmHandoff(@Valid @RequestBody HandoffConfirmRequest request) {
        return tagService.confirmHandoff(request);
    }

    @PostMapping("/handoffs/reject")
    public HandoffResponse rejectHandoff(@Valid @RequestBody HandoffRejectRequest request) {
        return tagService.rejectHandoff(request);
    }

    @PostMapping("/handoffs/cancel")
    public HandoffResponse cancelHandoff(@Valid @RequestBody HandoffCancelRequest request) {
        return tagService.cancelHandoff(request);
    }

    @GetMapping("/{tagCode}/timeline")
    public TagTimelineResponse tagTimeline(@PathVariable String tagCode) {
        return tagService.tagTimeline(tagCode);
    }

    @GetMapping("/animals/{animalCode}/timeline")
    public AnimalTimelineResponse animalTimeline(@PathVariable String animalCode) {
        return tagService.animalTimeline(animalCode);
    }
}
