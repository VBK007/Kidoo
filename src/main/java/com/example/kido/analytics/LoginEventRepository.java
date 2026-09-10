package com.example.kido.analytics;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface LoginEventRepository extends JpaRepository<LoginEvent, String> {
    List<LoginEvent> findByUserIdOrderByAtDesc(String userId);
}
