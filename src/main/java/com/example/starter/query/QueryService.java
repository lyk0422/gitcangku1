package com.example.starter.query;

import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.common.BizException;
import com.example.starter.query.QueryDtos.PackageView;
import com.example.starter.query.QueryDtos.SampleContextResponse;
import com.example.starter.query.QueryDtos.SealEventView;

/**
 * 背景查询：样本的层位、坐标格、UTC 采集时刻、所属包版本与每次封签状态。
 */
@Service
public class QueryService {

    private final JdbcClient jdbc;

    public QueryService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 按样本编号查询完整背景；样本不存在返回 422。 */
    @Transactional(readOnly = true)
    public SampleContextResponse sampleContext(String sampleNo) {
        record SampleRow(long id, String sampleNo, String gridRef, java.sql.Timestamp collectedAt, String status,
                String siteCode, String stratumNo) {
        }
        SampleRow sample = jdbc.sql("""
                SELECT s.id, s.sample_no, s.grid_ref, s.collected_at, s.status, t.site_code, t.stratum_no
                FROM sample s JOIN stratum t ON t.id = s.stratum_id WHERE s.sample_no=?""")
                .param(sampleNo)
                .query((rs, n) -> new SampleRow(rs.getLong(1), rs.getString(2), rs.getString(3),
                        rs.getTimestamp(4), rs.getString(5), rs.getString(6), rs.getString(7)))
                .optional()
                .orElseThrow(() -> BizException.unprocessable("样本不存在: " + sampleNo));
        record PkgRow(long id, String packageNo, String lineageNo, int version, String sealNo, String status) {
        }
        List<PkgRow> packages = jdbc.sql("""
                SELECT p.id, p.package_no, p.lineage_no, p.version, p.seal_no, p.status
                FROM seal_package p JOIN package_item i ON i.package_id = p.id
                WHERE i.sample_id = ? ORDER BY p.version, p.id""")
                .param(sample.id())
                .query((rs, n) -> new PkgRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getInt(4),
                        rs.getString(5), rs.getString(6)))
                .list();
        List<PackageView> views = packages.stream()
                .map(p -> new PackageView(p.packageNo(), p.lineageNo(), p.version(), p.sealNo(), p.status(),
                        eventsOf(p.id())))
                .toList();
        return new SampleContextResponse(sample.sampleNo(), sample.siteCode(), sample.stratumNo(), sample.gridRef(),
                sample.collectedAt().toInstant(), sample.status(), views);
    }

    private List<SealEventView> eventsOf(long packageId) {
        return jdbc.sql("SELECT event_type, seal_no, detail, occurred_at FROM seal_event WHERE package_id=? ORDER BY occurred_at, id")
                .param(packageId)
                .query((rs, n) -> new SealEventView(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getTimestamp(4).toInstant()))
                .list();
    }
}