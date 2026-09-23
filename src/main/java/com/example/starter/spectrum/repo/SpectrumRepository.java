package com.example.starter.spectrum.repo;

import com.example.starter.spectrum.domain.PlanRecord;
import com.example.starter.spectrum.domain.SpectrumNetwork;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/**
 * 频率协同数据访问层（H2 MODE=MySQL，参数化 SQL）。
 * 网络配置（台站、有向边）创建后只读；频道与版本通过乐观锁条件更新原子推进。
 */
@Repository
public class SpectrumRepository {

    private final JdbcTemplate jdbcTemplate;

    public SpectrumRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final RowMapper<SpectrumNetwork.Station> STATION_MAPPER = (rs, n) -> new SpectrumNetwork.Station(
            rs.getString("station_id"),
            rs.getInt("position"),
            rs.getInt("interference_budget"),
            rs.getInt("channel"));

    private static final RowMapper<SpectrumNetwork.Edge> EDGE_MAPPER = (rs, n) -> new SpectrumNetwork.Edge(
            rs.getString("from_station_id"),
            rs.getString("to_station_id"),
            rs.getInt("interference"));

    private static final RowMapper<PlanRecord> PLAN_MAPPER = (rs, n) -> {
        Timestamp ts = rs.getTimestamp("created_at");
        OffsetDateTime createdAt = ts == null ? null : ts.toInstant().atOffset(ZoneOffset.UTC);
        return new PlanRecord(
                rs.getLong("id"),
                rs.getString("network_id"),
                rs.getString("plan_key"),
                rs.getString("request_id"),
                rs.getInt("version_from"),
                rs.getInt("version_to"),
                rs.getString("normalized_request"),
                rs.getString("before_config"),
                rs.getString("after_config"),
                rs.getString("interference_summary"),
                createdAt);
    };

    public boolean networkExists(String networkId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM spectrum_network WHERE network_id = ?",
                Integer.class, networkId);
        return count != null && count > 0;
    }

    /**
     * 读取网络聚合（台站按定义顺序、边按定义顺序）。
     * 先取元数据并关闭结果集，再查子表，避免嵌套查询并发占用两个连接。
     */
    public Optional<SpectrumNetwork> findNetwork(String networkId) {
        List<Object[]> meta = jdbcTemplate.query(
                "SELECT network_id, name, version FROM spectrum_network WHERE network_id = ?",
                (rs, n) -> new Object[]{rs.getString("network_id"), rs.getString("name"),
                        rs.getInt("version")},
                networkId);
        if (meta.isEmpty()) {
            return Optional.empty();
        }
        Object[] m = meta.get(0);
        String id = (String) m[0];
        List<SpectrumNetwork.Station> stations = jdbcTemplate.query(
                "SELECT station_id, position, interference_budget, channel "
                        + "FROM spectrum_station WHERE network_id = ? ORDER BY position",
                STATION_MAPPER, id);
        List<SpectrumNetwork.Edge> edges = jdbcTemplate.query(
                "SELECT from_station_id, to_station_id, interference "
                        + "FROM spectrum_edge WHERE network_id = ? "
                        + "ORDER BY from_station_id, to_station_id",
                EDGE_MAPPER, id);
        return Optional.of(new SpectrumNetwork(id, (String) m[1], (Integer) m[2], stations, edges));
    }

    /**
     * 插入完整网络配置（网络、台站初始静默、边）。与调用方事务同生共死。
     */
    @Transactional
    public void insertNetwork(SpectrumNetwork network) {
        Timestamp now = Timestamp.from(java.time.Instant.now());
        jdbcTemplate.update(
                "INSERT INTO spectrum_network (network_id, name, version, created_at, updated_at) VALUES (?, ?, ?, ?, ?)",
                network.networkId(), network.name(), network.version(), now, now);
        for (SpectrumNetwork.Station station : network.stations()) {
            jdbcTemplate.update(
                    "INSERT INTO spectrum_station (network_id, station_id, position, interference_budget, channel) "
                            + "VALUES (?, ?, ?, ?, ?)",
                    network.networkId(), station.stationId(), station.position(),
                    station.interferenceBudget(), station.channel());
        }
        for (SpectrumNetwork.Edge edge : network.edges()) {
            jdbcTemplate.update(
                    "INSERT INTO spectrum_edge (network_id, from_station_id, to_station_id, interference) "
                            + "VALUES (?, ?, ?, ?)",
                    network.networkId(), edge.fromStationId(), edge.toStationId(), edge.interference());
        }
    }

    /**
     * 乐观锁条件更新版本；仅当库内版本仍为 expectedVersion 时生效。
     *
     * @return 受影响行数：1 表示抢到提交权，0 表示版本已被并发推进。
     */
    public int compareAndSetVersion(String networkId, int expectedVersion, int newVersion) {
        Timestamp now = Timestamp.from(java.time.Instant.now());
        return jdbcTemplate.update(
                "UPDATE spectrum_network SET version = ?, updated_at = ? "
                        + "WHERE network_id = ? AND version = ?",
                newVersion, now, networkId, expectedVersion);
    }

    /**
     * 原子写回单个台站频道（仅在校验通过后的提交阶段调用）。
     */
    public void updateChannel(String networkId, String stationId, int channel) {
        jdbcTemplate.update(
                "UPDATE spectrum_station SET channel = ? WHERE network_id = ? AND station_id = ?",
                channel, networkId, stationId);
    }

    public void insertPlan(PlanRecord record) {
        jdbcTemplate.update(
                "INSERT INTO spectrum_plan (network_id, plan_key, request_id, version_from, version_to, "
                        + "normalized_request, before_config, after_config, interference_summary, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                record.networkId(), record.planKey(), record.requestId(),
                record.versionFrom(), record.versionTo(),
                record.normalizedRequest(), record.beforeConfig(), record.afterConfig(),
                record.interferenceSummary(),
                record.createdAt() == null ? null : Timestamp.from(record.createdAt().toInstant()));
    }

    public List<PlanRecord> findPlans(String networkId) {
        return jdbcTemplate.query(
                "SELECT id, network_id, plan_key, request_id, version_from, version_to, "
                        + "normalized_request, before_config, after_config, interference_summary, created_at "
                        + "FROM spectrum_plan WHERE network_id = ? ORDER BY id",
                PLAN_MAPPER, networkId);
    }

    public Optional<PlanRecord> findPlanByKey(String planKey) {
        return jdbcTemplate.query(
                "SELECT id, network_id, plan_key, request_id, version_from, version_to, "
                        + "normalized_request, before_config, after_config, interference_summary, created_at "
                        + "FROM spectrum_plan WHERE plan_key = ?",
                rs -> rs.next() ? Optional.of(PLAN_MAPPER.mapRow(rs, 1)) : Optional.empty(),
                planKey);
    }

    public Optional<PlanRecord> findPlanByRequestId(String requestId) {
        return jdbcTemplate.query(
                "SELECT id, network_id, plan_key, request_id, version_from, version_to, "
                        + "normalized_request, before_config, after_config, interference_summary, created_at "
                        + "FROM spectrum_plan WHERE request_id = ?",
                rs -> rs.next() ? Optional.of(PLAN_MAPPER.mapRow(rs, 1)) : Optional.empty(),
                requestId);
    }

    public Optional<IdempotentRecord> findRequest(String requestId) {
        return jdbcTemplate.query(
                "SELECT request_id, operation, target_network, params_hash, result_status, result_body "
                        + "FROM spectrum_request WHERE request_id = ?",
                rs -> rs.next()
                        ? Optional.of(new IdempotentRecord(
                                rs.getString("request_id"),
                                rs.getString("operation"),
                                rs.getString("target_network"),
                                rs.getString("params_hash"),
                                rs.getInt("result_status"),
                                rs.getString("result_body")))
                        : Optional.empty(),
                requestId);
    }

    /**
     * 写入成功结果的幂等记录；唯一键冲突由调用方按并发重放处理。
     */
    public void insertRequest(IdempotentRecord record) {
        jdbcTemplate.update(
                "INSERT INTO spectrum_request (request_id, operation, target_network, params_hash, "
                        + "result_status, result_body, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                record.requestId(), record.operation(), record.targetNetwork(), record.paramsHash(),
                record.resultStatus(), record.resultBody(), Timestamp.from(java.time.Instant.now()));
    }

    /**
     * requestId 幂等记录：仅成功（2xx）写操作入库；失败不占键。
     */
    public record IdempotentRecord(
            String requestId,
            String operation,
            String targetNetwork,
            String paramsHash,
            int resultStatus,
            String resultBody
    ) {
    }
}
