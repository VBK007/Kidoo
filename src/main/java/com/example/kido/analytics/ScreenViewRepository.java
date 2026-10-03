package com.example.kido.analytics;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ScreenViewRepository extends JpaRepository<ScreenView, String> {

    List<ScreenView> findTop50ByUserIdOrderByAtDesc(String userId);

    /**
     * A page of the most recent screens across every account, newest first — the raw
     * material for the admin "who's where, right now" feed.
     *
     * <p>Fetched as a flat page and reduced to one row per account in Java rather than
     * with a correlated-subquery "latest row per group" — the row count read here is
     * bounded and small, and that shape of query is exactly where H2 and PostgreSQL
     * part ways.
     */
    List<ScreenView> findTop200ByOrderByAtDesc();
}
