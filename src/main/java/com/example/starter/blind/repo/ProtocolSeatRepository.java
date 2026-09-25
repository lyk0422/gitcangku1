package com.example.starter.blind.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * V2 及以上协议席位（盲底）数据访问；修订生效时按规范化比例一次性生成，之后不可改。
 * 席位处理映射仅落库，不输出到日志或普通接口。
 */
@Repository
public class ProtocolSeatRepository {

    private final JdbcTemplate jdbc;

    public ProtocolSeatRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(String experimentId, int version, int blockNo, int seatNo, String treatment) {
        jdbc.update(
                "INSERT INTO protocol_seat (experiment_id, version, block_no, seat_no, treatment) "
                        + "VALUES (?, ?, ?, ?, ?)",
                experimentId, version, blockNo, seatNo, treatment);
    }

    /** 查询某版本某席位的处理代码（盲底，仅供揭盲批准使用）。 */
    public String findTreatment(String experimentId, int version, int blockNo, int seatNo) {
        return jdbc.query(
                "SELECT treatment FROM protocol_seat "
                        + "WHERE experiment_id = ? AND version = ? AND block_no = ? AND seat_no = ?",
                rs -> rs.next() ? rs.getString("treatment") : null,
                experimentId, version, blockNo, seatNo);
    }
}
