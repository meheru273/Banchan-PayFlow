package com.payflow.payment.service;

import com.payflow.payment.api.dto.CreatePaymentRequest;
import com.payflow.payment.api.dto.PaymentResponse;
import com.payflow.payment.domain.LedgerDirection;
import com.payflow.payment.domain.LedgerEntry;
import com.payflow.payment.domain.Payment;
import com.payflow.payment.domain.PaymentStatus;
import com.payflow.payment.domain.Wallet;
import com.payflow.payment.error.DomainValidationException;
import com.payflow.payment.repo.LedgerEntryRepository;
import com.payflow.payment.repo.PaymentRepository;
import com.payflow.payment.repo.WalletRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * Creates a payment and posts the balanced ledger pair in one transaction.
 *
 * Tier 1 shortcut: completion happens synchronously here. In Tier 2 the
 * provider webhook completes the payment and the ledger posting moves to
 * ledger-worker via a RabbitMQ `payment.completed` event.
 */
@Service
public class PaymentProcessor {

    /** Owner name of the internal clearing wallet that balances every customer entry. */
    public static final String TREASURY_OWNER = "TREASURY";

    private final PaymentRepository paymentRepository;
    private final WalletRepository walletRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final SecureRandom random = new SecureRandom();

    public PaymentProcessor(PaymentRepository paymentRepository,
                            WalletRepository walletRepository,
                            LedgerEntryRepository ledgerEntryRepository) {
        this.paymentRepository = paymentRepository;
        this.walletRepository = walletRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
    }

    @Transactional
    public PaymentResponse process(CreatePaymentRequest request) {
        Wallet wallet = walletRepository.findById(request.walletId())
                .orElseThrow(() -> new DomainValidationException(
                        "Wallet " + request.walletId() + " does not exist"));
        if (!wallet.getCurrency().equals(request.currency())) {
            throw new DomainValidationException(
                    "Payment currency " + request.currency()
                            + " does not match wallet currency " + wallet.getCurrency());
        }
        Wallet treasury = walletRepository.findFirstByOwner(TREASURY_OWNER)
                .orElseThrow(() -> new IllegalStateException("Treasury wallet is missing — was demo data seeded?"));

        Payment payment = paymentRepository.save(new Payment(
                wallet.getId(), request.amount(), request.currency(), request.cardRef()));

        payment.setProviderRef("SIM-" + HexFormat.of().formatHex(random.generateSeed(8)));
        postDoubleEntry(payment, treasury, wallet);
        payment.setStatus(PaymentStatus.COMPLETED);

        verifyZeroSum(payment.getId());
        return PaymentResponse.from(payment);
    }

    private void postDoubleEntry(Payment payment, Wallet treasury, Wallet customer) {
        BigDecimal amount = payment.getAmount();
        ledgerEntryRepository.saveAll(List.of(
                new LedgerEntry(payment.getId(), treasury.getId(), LedgerDirection.DEBIT, amount),
                new LedgerEntry(payment.getId(), customer.getId(), LedgerDirection.CREDIT, amount)));
        treasury.debit(amount);
        customer.credit(amount);
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
