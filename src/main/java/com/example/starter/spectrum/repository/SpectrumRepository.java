package com.example.starter.spectrum.repository;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 频率协同数据访问层；全部 SQL 参数化，兼容 H2（MODE=MySQL）与 MySQL 语法。
 */
@Repository
public class SpectrumRepository {

    private static final RowMapper<NetworkRow> NETWORK_MAPPER = (rs, n) -> new NetworkRow(
            rs.getLong("id"),
            rs.getString("network_id"),
            rs.getInt("version"),
            rs.getLong("created_at"),
            rs.getLong("updated_at"));

    private static final RowMapper<StationRow> STATION_MAPPER = (rs, n) -> new StationRow(
            rs.getLong("id"),
            rs.getString("station_id"),
            rs.getInt("budget"),
            rs.getInt("channel"));

    private static final RowMapper<EdgeRow> EDGE_MAPPER = (rs, n) -> new EdgeRow(
            rs.getString("from_station"),
            rs.getString("to_station"),
            rs.getInt("amount"));

    private static final RowMapper<PlanRow> PLAN_MAPPER = (rs, n) -> new PlanRow(
            rs.getString("plan_key"),
            rs.getString("request_id"),
            rs.getInt("version"),
            rs.getString("channels_before"),
            rs.getString("channels_after"),
            rs.getString("summary"),
            rs.getLong("created_at"));

    private static final RowMapper<RequestRow> REQUEST_MAPPER = (rs, n) -> new RequestRow(
            rs.getString("request_id"),
            rs.getString("request_kind"),
            rs.getString("network_id"),
            rs.getString("plan_key"),
            rs.getString("param_digest"),
            rs.getInt("http_status"),
            rs.getString("response_body"),
            rs.getLong("created_at"));

    private final JdbcTemplate jdbcTemplate;

    public SpectrumRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 新建网络（version 从 1 开始），返回自增主键。 */
    public long insertNetwork(String networkId, long now) {
        jdbcTemplate.update(
                "INSERT INTO spectrum_network (network_id, version, created_at, updated_at) VALUES (?, ?, ?, ?)",
                networkId, 1, now, now);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM spectrum_network WHERE network_id = ?", Long.class, networkId);
    }

    /** 批量写入台站，台站初始静默 channel=0。 */
    public void insertStations(long networkRef, List<StationInput> stations) {
        jdbcTemplate.batchUpdate(
                "INSERT INTO spectrum_station (network_ref, station_id, budget, channel) VALUES (?, ?, ?, 0)",
                stations, stations.size(), (ps, station) -> {
                    ps.setLong(1, networkRef);
                    ps.setString(2, station.stationId());
                    ps.setInt(3, station.budget());
                });
    }

    /** 批量写入有向干扰边。 */
    public void insertEdges(long networkRef, List<EdgeInput> edges) {
        jdbcTemplate.batchUpdate(
                "INSERT INTO spectrum_edge (network_ref, from_station, to_station, amount) VALUES (?, ?, ?, ?)",
                edges, Math.max(1, edges.size()), (ps, edge) -> {
                    ps.setLong(1, networkRef);
                    ps.setString(2, edge.from());
                    ps.setString(3, edge.to());
                    ps.setInt(4, edge.amount());
                });
    }

    /** 按业务ID查询网络，不存在返回 null。 */
    public NetworkRow findNetwork(String networkId) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT id, network_id, version, created_at, updated_at "
                            + "FROM spectrum_network WHERE network_id = ?",
                    NETWORK_MAPPER, networkId);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    /**
     * 锁定网络行（SELECT ... FOR UPDATE），供方案提交串行化版本裁决；
     * 行不存在返回 null。
     */
    public NetworkRow lockNetwork(String networkId) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT id, network_id, version, created_at, updated_at "
                            + "FROM spectrum_network WHERE network_id = ? FOR UPDATE",
                    NETWORK_MAPPER, networkId);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    /** 台站按 station_id 升序返回。 */
    public List<StationRow> findStations(long networkRef) {
        return jdbcTemplate.query(
                "SELECT id, station_id, budget, channel FROM spectrum_station "
                        + "WHERE network_ref = ? ORDER BY station_id ASC",
                STATION_MAPPER, networkRef);
    }

    /** 有向边按 (from_station, to_station) 升序返回。 */
    public List<EdgeRow> findEdges(long networkRef) {
        return jdbcTemplate.query(
                "SELECT from_station, to_station, amount FROM spectrum_edge "
                        + "WHERE network_ref = ? ORDER BY from_station ASC, to_station ASC",
                EDGE_MAPPER, networkRef);
    }

    /**
     * 原子更新全部台站频道与网络版本；频道与版本在同一条事务内替换，
     * 不存在先逐台修改再校验的中间状态。
     */
    public void replaceChannelsAndBumpVersion(long networkRef, List<StationChannel> channels,
                                              int newVersion, long now) {
        jdbcTemplate.batchUpdate(
                "UPDATE spectrum_station SET channel = ? WHERE network_ref = ? AND station_id = ?",
                channels, channels.size(), (ps, item) -> {
                    ps.setInt(1, item.channel());
                    ps.setLong(2, networkRef);
                    ps.setString(3, item.stationId());
                });
        jdbcTemplate.update(
                "UPDATE spectrum_network SET version = ?, updated_at = ? WHERE id = ?",
                newVersion, now, networkRef);
    }

    /** 写入不可变方案历史记录。 */
    public void insertPlan(PlanInput plan, long now) {
        jdbcTemplate.update(
                "INSERT INTO spectrum_plan (network_ref, plan_key, request_id, version, "
                        + "channels_before, channels_after, summary, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                plan.networkRef(), plan.planKey(), plan.requestId(), plan.version(),
                plan.channelsBefore(), plan.channelsAfter(), plan.summary(), now);
    }

    /** 方案记录按版本升序返回。 */
    public List<PlanRow> findPlans(long networkRef) {
        return jdbcTemplate.query(
                "SELECT plan_key, request_id, version, channels_before, channels_after, summary, created_at "
                        + "FROM spectrum_plan WHERE network_ref = ? ORDER BY version ASC",
                PLAN_MAPPER, networkRef);
    }

    /** 按全局 planKey 查询方案，不存在返回 null。 */
    public PlanRow findPlanByKey(String planKey) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT plan_key, request_id, version, channels_before, channels_after, summary, created_at "
                            + "FROM spectrum_plan WHERE plan_key = ?",
                    PLAN_MAPPER, planKey);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    /** 仅成功写请求占键；失败事务回滚，不会留下请求记录。 */
    public void insertRequest(RequestInput request, long now) {
        jdbcTemplate.update(
                "INSERT INTO spectrum_request (request_id, request_kind, network_id, plan_key, "
                        + "param_digest, http_status, response_body, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                request.requestId(), request.kind(), request.networkId(), request.planKey(),
                request.paramDigest(), request.httpStatus(), request.responseBody(), now);
    }

    /** 按全局 requestId 查询首次成功记录，不存在返回 null。 */
    public RequestRow findRequest(String requestId) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT request_id, request_kind, network_id, plan_key, param_digest, "
                            + "http_status, response_body, created_at "
                            + "FROM spectrum_request WHERE request_id = ?",
                    REQUEST_MAPPER, requestId);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    /** 网络行。 */
    public record NetworkRow(long id, String networkId, int version, long createdAt, long updatedAt) {
    }

    /** 台盛行。 */
    public record StationRow(long id, String stationId, int budget, int channel) {
    }

    /** 有向边行。 */
    public record EdgeRow(String from, String to, int amount) {
    }

    /** 方案历史行，快照字段为规范化 JSON 文本。 */
    public record PlanRow(String planKey, String requestId, int version,
                          String channelsBefore, String channelsAfter, String summary, long createdAt) {
    }

    /** 写请求幂等记录行。 */
    public record RequestRow(String requestId, String kind, String networkId, String planKey,
                             String paramDigest, int httpStatus, String responseBody, long createdAt) {
    }

    /** 台站批量写入参数。 */
    public record StationInput(String stationId, int budget) {
    }

    /** 有向边批量写入参数。 */
    public record EdgeInput(String from, String to, int amount) {
    }

    /** 台站频道批量更新参数。 */
    public record StationChannel(String stationId, int channel) {
    }

    /** 方案记录写入参数。 */
    public record PlanInput(long networkRef, String planKey, String requestId, int version,
                            String channelsBefore, String channelsAfter, String summary) {
    }

    /** 幂等请求记录写入参数。 */
    public record RequestInput(String requestId, String kind, String networkId, String planKey,
                               String paramDigest, int httpStatus, String responseBody) {
    }
}
