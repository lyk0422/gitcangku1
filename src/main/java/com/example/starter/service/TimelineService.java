package com.example.starter.service;

import com.example.starter.error.ApiException;
import com.example.starter.model.AssignmentRow;
import com.example.starter.model.BoardingPointChangeRow;
import com.example.starter.model.PilotRow;
import com.example.starter.model.SegmentRow;
import com.example.starter.repo.AssignmentRepository;
import com.example.starter.repo.BoardingPointChangeRepository;
import com.example.starter.repo.PilotRepository;
import com.example.starter.repo.SegmentRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 时间轴查询：按引航员或按船舶。
 */
@Service
public class TimelineService {

    private final PilotRepository pilotRepository;
    private final SegmentRepository segmentRepository;
    private final AssignmentRepository assignmentRepository;
    private final BoardingPointChangeRepository changeRepository;

    public TimelineService(PilotRepository pilotRepository, SegmentRepository segmentRepository,
                           AssignmentRepository assignmentRepository,
                           BoardingPointChangeRepository changeRepository) {
        this.pilotRepository = pilotRepository;
        this.segmentRepository = segmentRepository;
        this.assignmentRepository = assignmentRepository;
        this.changeRepository = changeRepository;
    }

    /**
     * 引航员时间轴：其全部指派及对应航段，按预计开始时间排序。
     */
    @Transactional(readOnly = true)
    public List<PilotTimelineEntry> pilotTimeline(String pilotCode) {
        PilotRow pilot = pilotRepository.findByCode(pilotCode)
                .orElseThrow(() -> ApiException.notFound("PILOT_NOT_FOUND", "引航员不存在: " + pilotCode));
        return assignmentRepository.findByPilotId(pilot.id()).stream()
                .map(a -> new PilotTimelineEntry(a, segmentRepository.findById(a.segmentId())
                        .orElseThrow()))
                .sorted((x, y) -> x.segment().estStartUtc().compareTo(y.segment().estStartUtc()))
                .toList();
    }

    /**
     * 船舶时间轴：其全部航段、当前指派及登轮点变更历史，按预计开始时间排序。
     */
    @Transactional(readOnly = true)
    public List<VesselTimelineEntry> vesselTimeline(String vesselCode) {
        List<SegmentRow> segments = segmentRepository.findByVesselCode(vesselCode);
        return segments.stream()
                .map(s -> new VesselTimelineEntry(s,
                        assignmentRepository.findLatestBySegmentId(s.id()).orElse(null),
                        changeRepository.findBySegmentId(s.id())))
                .toList();
    }

    /**
     * 引航员时间轴条目。
     */
    public record PilotTimelineEntry(AssignmentRow assignment, SegmentRow segment) {
    }

    /**
     * 船舶时间轴条目。
     */
    public record VesselTimelineEntry(SegmentRow segment, AssignmentRow latestAssignment,
                                      List<BoardingPointChangeRow> boardingPointHistory) {
    }
}