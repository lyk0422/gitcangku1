package com.example.gitcangku1.service;

import com.example.gitcangku1.domain.Adjudication;
import com.example.gitcangku1.domain.Errata;
import com.example.gitcangku1.domain.Measurement;
import com.example.gitcangku1.domain.ReleaseItem;
import com.example.gitcangku1.domain.ReleaseOrder;
import java.time.Instant;
import java.util.List;

/** 查询视图模型：展示目标版本、每卷原测与复检、采纳依据和最终色差。 */
public final class Views {

    private Views() {
    }

    public record MeasurementView(long id, int rollNo, String type, double l, double a, double b,
            Instant measuredAt, int targetVersion, String requestId) {

        static MeasurementView of(Measurement m) {
            return new MeasurementView(m.id(), m.rollNo(), m.type().name(), m.l(), m.a(), m.b(),
                    m.measuredAt(), m.targetVersion(), m.requestId());
        }
    }

    public record AdjudicationView(int rollNo, String decision, String inspector, Instant decidedAt) {

        static AdjudicationView of(Adjudication a) {
            return new AdjudicationView(a.rollNo(), a.decision().name(), a.inspector(), a.decidedAt());
        }
    }

    public record RollView(int rollNo, MeasurementView original, MeasurementView recheck,
            AdjudicationView adjudication, String adoptedSource, Double deltaE, Boolean pass) {
    }

    public record ReleaseItemView(int rollNo, String adoptedSource, double l, double a, double b,
            double deltaE, Instant measuredAt) {

        static ReleaseItemView of(ReleaseItem i) {
            return new ReleaseItemView(i.rollNo(), i.adoptedSource().name(), i.l(), i.a(), i.b(),
                    i.deltaE(), i.measuredAt());
        }
    }

    public record ReleaseView(long releaseId, int targetVersion, Instant releasedAt,
            List<ReleaseItemView> items, List<ErrataView> errata) {

        static ReleaseView of(ReleaseOrder o, List<ReleaseItem> items, List<Errata> errata) {
            return new ReleaseView(o.id(), o.targetVersion(), o.releasedAt(),
                    items.stream().map(ReleaseItemView::of).toList(),
                    errata.stream().map(ErrataView::of).toList());
        }
    }

    public record ErrataView(long id, String note, Instant createdAt) {

        static ErrataView of(Errata e) {
            return new ErrataView(e.id(), e.note(), e.createdAt());
        }
    }

    public record BatchView(long batchId, String batchNo, double targetL, double targetA,
            double targetB, int targetVersion, int expectedRolls, String status,
            List<RollView> rolls, ReleaseView release) {
    }
}