package com.isym444.networth.repository;

import com.isym444.networth.model.NetWorthSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface NetWorthSnapshotRepository extends JpaRepository<NetWorthSnapshot, Long> {

    List<NetWorthSnapshot> findByCurrencyOrderByTimestampAsc(String currency);

    @Query("SELECT DISTINCT s.currency FROM NetWorthSnapshot s ORDER BY s.currency")
    List<String> findDistinctCurrencies();
}
