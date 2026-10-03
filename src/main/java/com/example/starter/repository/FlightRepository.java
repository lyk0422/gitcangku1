package com.example.starter.repository;

import com.example.starter.domain.Flight;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 航班合成数据访问。 */
@Repository
public class FlightRepository {

    private final JdbcTemplate jdbc;

    public FlightRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Flight> findById(long id) {
        return jdbc.query("SELECT id, flight_no, airport_id FROM flight WHERE id = ?",
                (rs, n) -> new Flight(rs.getLong("id"), rs.getString("flight_no"), rs.getLong("airport_id")),
                id).stream().findFirst();
    }
}