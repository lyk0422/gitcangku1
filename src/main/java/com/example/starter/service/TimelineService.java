package com.example.starter.service;

import com.example.starter.domain.TimelineEvent;
import com.example.starter.repo.AttachmentRepository;
import com.example.starter.repo.RejectionLogRepository;
import com.example.starter.repo.TransferRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 历史时间线查询：按标签或动物组合附着、摘除、交接与拒绝事件，
 * 展示站点、交接确认时刻及拒绝原因。
 */
@Service
public class TimelineService {

    private final AttachmentRepository attachments;
    private final TransferRepository transfers;
    private final RejectionLogRepository rejections;

    public TimelineService(AttachmentRepository attachments, TransferRepository transfers,
            RejectionLogRepository rejections) {
        this.attachments = attachments;
        this.transfers = transfers;
        this.rejections = rejections;
    }

    /**
     * 按标签查询完整时间线，按发生时刻升序。
     */
    @Transactional(readOnly = true)
    public List<TimelineEvent> tagTimeline(String tagCode) {
        List<TimelineEvent> events = new ArrayList<>();
        attachments.listByTag(tagCode).forEach(a -> {
            events.add(new TimelineEvent("ATTACH", a.attachedAt(), a.tagCode(), a.animalCode(),
                    a.stationCode(), null, null, null, null));
            if (a.detachedAt() != null) {
                events.add(new TimelineEvent("DETACH", a.detachedAt(), a.tagCode(), a.animalCode(),
                        a.stationCode(), null, null, null, null));
            }
        });
        transfers.listByTag(tagCode).forEach(t -> {
            events.add(new TimelineEvent("TRANSFER_INITIATED", t.initiatedAt(), t.tagCode(), null,
                    null, t.fromStation(), t.toStation(), null, null));
            if (t.confirmedAt() != null) {
                events.add(new TimelineEvent("TRANSFER_CONFIRMED", t.confirmedAt(), t.tagCode(),
                        null, null, t.fromStation(), t.toStation(), t.confirmedAt(), null));
            }
            if (t.cancelledAt() != null) {
                events.add(new TimelineEvent("TRANSFER_CANCELLED", t.cancelledAt(), t.tagCode(),
                        null, null, t.fromStation(), t.toStation(), null, null));
            }
        });
        events.addAll(rejections.listByTag(tagCode));
        events.sort(Comparator.comparing(TimelineEvent::occurredAt));
        return events;
    }

    /**
     * 按动物查询完整时间线，按发生时刻升序。
     */
    @Transactional(readOnly = true)
    public List<TimelineEvent> animalTimeline(String animalCode) {
        List<TimelineEvent> events = new ArrayList<>();
        attachments.listByAnimal(animalCode).forEach(a -> {
            events.add(new TimelineEvent("ATTACH", a.attachedAt(), a.tagCode(), a.animalCode(),
                    a.stationCode(), null, null, null, null));
            if (a.detachedAt() != null) {
                events.add(new TimelineEvent("DETACH", a.detachedAt(), a.tagCode(), a.animalCode(),
                        a.stationCode(), null, null, null, null));
            }
        });
        events.addAll(rejections.listByAnimal(animalCode));
        events.sort(Comparator.comparing(TimelineEvent::occurredAt));
        return events;
    }
}