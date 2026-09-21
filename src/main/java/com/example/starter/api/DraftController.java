package com.example.starter.api;

import com.example.starter.api.dto.Requests;
import com.example.starter.api.dto.Responses;
import com.example.starter.common.ApiException;
import com.example.starter.common.TimeSupport;
import com.example.starter.domain.DraftSegment;
import com.example.starter.service.DraftService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * 编排草稿接口：整份替换与查询。
 */
@RestController
@RequestMapping("/channels/{channelId}/days/{day}/draft")
public class DraftController {

    private final DraftService draftService;

    public DraftController(DraftService draftService) {
        this.draftService = draftService;
    }

    @PutMapping
    public Responses.DraftView replace(
            @PathVariable String channelId,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate day,
            @RequestBody Requests.ReplaceDraft request) {
        if (request.expectedDraftVersion() == null) {
            throw ApiException.badRequest("INVALID_DRAFT_REQUEST", "expectedDraftVersion 不能为空");
        }
        List<DraftSegment> segments = request.segments() == null ? List.of()
                : request.segments().stream().map(DraftController::toSegment).toList();
        return ViewMapper.toView(draftService.replace(channelId, day, request.requestId(),
                request.expectedDraftVersion(), segments));
    }

    @GetMapping
    public Responses.DraftView get(
            @PathVariable String channelId,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate day) {
        return ViewMapper.toView(draftService.get(channelId, day));
    }

    private static DraftSegment toSegment(Requests.SegmentInput input) {
        return new DraftSegment(input.segmentId(), input.assetId(),
                TimeSupport.parse(input.start(), "segments.start"),
                TimeSupport.parse(input.end(), "segments.end"));
    }
}
