package com.isym444.networth.repository;

import com.isym444.networth.model.HealthInsight;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface HealthInsightRepository extends JpaRepository<HealthInsight, Long> {

    Optional<HealthInsight> findFirstByOrderByGeneratedAtDesc();
}
