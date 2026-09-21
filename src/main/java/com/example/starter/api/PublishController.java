package com.example.starter.api;

import com.example.starter.api.dto.Requests;
import com.example.starter.api.dto.Responses;
import com.example.starter.common.ApiException;
import com.example.starter.service.PublishService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * 发布接口：将草稿原子发布为只读快照。
 */
@RestController
public class PublishController {

    private final PublishService publishService;

    public PublishController(PublishService publishService) {
        this.publishService = publishService;
    }

    @PostMapping("/channels/{channelId}/days/{day}/publish")
    public Responses.PublishedView publish(
            @PathVariable String channelId,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate day,
            @RequestBody Requests.Publish request) {
        if (request.draftVersion() == null || request.expectedPublishedVersion() == null) {
            throw ApiException.badRequest("INVALID_PUBLISH_REQUEST",
                    "draftVersion 与 expectedPublishedVersion 不能为空");
        }
        return ViewMapper.toView(publishService.publish(channelId, day, request.requestId(),
                request.draftVersion(), request.expectedPublishedVersion()));
    }

    @GetMapping("/channels/{channelId}/days/{day}/published")
    public Responses.PublishedView get(
            @PathVariable String channelId,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate day) {
        return ViewMapper.toView(publishService.get(channelId, day));
    }
}
