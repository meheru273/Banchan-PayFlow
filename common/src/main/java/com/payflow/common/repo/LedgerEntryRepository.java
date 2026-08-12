package com.payflow.common.repo;

import com.payflow.common.domain.LedgerEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, UUID> {

    List<LedgerEntry> findByWalletIdOrderByCreatedAtDesc(UUID walletId);

    boolean existsByPaymentId(UUID paymentId);

    /**
     * Signed sum of a payment's entries (CREDIT positive, DEBIT negative).
     * The double-entry invariant requires this to be exactly zero.
     */
    @Query(value = """
            SELECT COALESCE(SUM(CASE WHEN direction = 'CREDIT' THEN amount ELSE -amount END), 0)
            FROM ledger_entry WHERE payment_id = :paymentId
            """, nativeQuery = true)
    BigDecimal signedSumForPayment(@Param("paymentId") UUID paymentId);
}
