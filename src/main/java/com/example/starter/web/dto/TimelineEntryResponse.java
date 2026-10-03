package com.example.starter.web.dto;

import com.example.starter.service.DeicingService.TimelineEntry;

/** 时间轴条目：时隙加完成记录（未完成时completion为空）。 */
public record TimelineEntryResponse(SlotResponse slot, CompletionResponse completion) {

    public static TimelineEntryResponse of(TimelineEntry entry) {
        return new TimelineEntryResponse(SlotResponse.of(entry.slot()),
                entry.completion() == null ? null : CompletionResponse.of(entry.completion()));
    }
}