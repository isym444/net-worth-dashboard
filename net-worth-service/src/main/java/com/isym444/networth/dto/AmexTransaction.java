package com.isym444.networth.dto;

import java.time.LocalDate;

public record AmexTransaction(
        LocalDate date,
        String payeeName,
        String categoryName,
        double amount,
        String memo,
        String accountName) {
}
