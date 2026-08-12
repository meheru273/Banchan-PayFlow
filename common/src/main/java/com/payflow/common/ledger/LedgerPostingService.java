package com.payflow.common.ledger;

import com.payflow.common.domain.LedgerDirection;
import com.payflow.common.domain.LedgerEntry;
import com.payflow.common.domain.Payment;
import com.payflow.common.domain.PaymentStatus;
import com.payflow.common.domain.Wallet;
import com.payflow.common.repo.LedgerEntryRepository;
import com.payflow.common.repo.PaymentRepository;
import com.payflow.common.repo.WalletRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Posts the balanced DEBIT/CREDIT pair for a completed payment. Shared between
 * payment-service (embedded mode, when no broker is configured) and
 * ledger-worker (the RabbitMQ consumer), so the posting rules cannot drift.
 */
@Service
public class LedgerPostingService {

    /** Owner name of the internal clearing wallet that balances every customer entry. */
    public static final String TREASURY_OWNER = "TREASURY";

    private static final Logger log = LoggerFactory.getLogger(LedgerPostingService.class);

    private final PaymentRepository paymentRepository;
    private final WalletRepository walletRepository;
    private final LedgerEntryRepository ledgerEntryRepository;

    public LedgerPostingService(PaymentRepository paymentRepository,
                                WalletRepository walletRepository,
                                LedgerEntryRepository ledgerEntryRepository) {
        this.paymentRepository = paymentRepository;
        this.walletRepository = walletRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
    }

    /**
     * Idempotent: a redelivered event (RabbitMQ is at-least-once) must not
     * double-post, so existing entries for the payment short-circuit.
     */
    @Transactional
    public void postForPayment(UUID paymentId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new IllegalArgumentException("Payment " + paymentId + " does not exist"));
        if (payment.getStatus() == PaymentStatus.COMPLETED) {
            log.info("Payment {} already completed — skipping duplicate posting", paymentId);
            return;
        }
        if (ledgerEntryRepository.existsByPaymentId(paymentId)) {
            log.info("Ledger entries already exist for payment {} — marking completed only", paymentId);
            payment.setStatus(PaymentStatus.COMPLETED);
            return;
        }

        Wallet customer = walletRepository.findById(payment.getWalletId())
                .orElseThrow(() -> new IllegalStateException(
                        "Wallet " + payment.getWalletId() + " missing for payment " + paymentId));
        Wallet treasury = walletRepository.findFirstByOwner(TREASURY_OWNER)
                .orElseThrow(() -> new IllegalStateException("Treasury wallet is missing — was demo data seeded?"));

        BigDecimal amount = payment.getAmount();
        ledgerEntryRepository.saveAll(List.of(
                new LedgerEntry(paymentId, treasury.getId(), LedgerDirection.DEBIT, amount),
                new LedgerEntry(paymentId, customer.getId(), LedgerDirection.CREDIT, amount)));
        treasury.debit(amount);
        customer.credit(amount);
        payment.setStatus(PaymentStatus.COMPLETED);

        verifyZeroSum(paymentId);
    }

    @Transactional
    public void markFailed(UUID paymentId) {
        paymentRepository.findById(paymentId).ifPresent(p -> p.setStatus(PaymentStatus.FAILED));
    }

    private void verifyZeroSum(UUID paymentId) {
        ledgerEntryRepository.flush();
        BigDecimal sum = ledgerEntryRepository.signedSumForPayment(paymentId);
        if (sum.signum() != 0) {
            throw new IllegalStateException(
                    "Double-entry invariant violated for payment " + paymentId + ": signed sum is " + sum);
        }
    }
}
