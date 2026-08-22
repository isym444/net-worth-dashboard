package com.isym444.networth.dto;

import com.isym444.networth.model.NetWorthSnapshot;

import java.util.List;

public record NetWorthHistoryResponse(
        List<NetWorthSnapshot> snapshots,
        Double expectedNetWorthAt40,
        Double expectedNetWorthAt50,
        Double expectedNetWorthAt60,
        Double expectedNetWorthAt65,
        Double monthlyContribution) {
}
