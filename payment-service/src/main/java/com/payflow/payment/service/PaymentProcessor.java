package com.payflow.payment.service;

import com.payflow.common.domain.Payment;
import com.payflow.common.domain.Wallet;
import com.payflow.common.ledger.LedgerPostingService;
import com.payflow.common.repo.PaymentRepository;
import com.payflow.common.repo.WalletRepository;
import com.payflow.payment.api.dto.CreatePaymentRequest;
import com.payflow.payment.api.dto.PaymentResponse;
import com.payflow.payment.error.DomainValidationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * Creates payments in PENDING state. Completion is asynchronous: the simulated
 * provider confirms via an HMAC-signed webhook, which publishes
 * `payment.completed` to RabbitMQ for ledger-worker to post the ledger pair
 * (or posts directly when messaging is disabled — the no-broker dev mode).
 */
@Service
public class PaymentProcessor {

    private final PaymentRepository paymentRepository;
    private final WalletRepository walletRepository;
    private final LedgerPostingService ledgerPostingService;
    private final SecureRandom random = new SecureRandom();

    public PaymentProcessor(PaymentRepository paymentRepository,
                            WalletRepository walletRepository,
                            LedgerPostingService ledgerPostingService) {
        this.paymentRepository = paymentRepository;
        this.walletRepository = walletRepository;
        this.ledgerPostingService = ledgerPostingService;
    }

    @Transactional
    public PaymentResponse process(CreatePaymentRequest request) {
        return PaymentResponse.from(createValidated(request));
    }

    /** Seeding path: completes synchronously so demo data is consistent at boot. */
    @Transactional
    public void processAndCompleteForSeed(CreatePaymentRequest request) {
        Payment payment = createValidated(request);
        ledgerPostingService.postForPayment(payment.getId());
    }

    private Payment createValidated(CreatePaymentRequest request) {
        Wallet wallet = walletRepository.findById(request.walletId())
                .orElseThrow(() -> new DomainValidationException(
                        "Wallet " + request.walletId() + " does not exist"));
        if (!wallet.getCurrency().equals(request.currency())) {
            throw new DomainValidationException(
                    "Payment currency " + request.currency()
                            + " does not match wallet currency " + wallet.getCurrency());
        }
        walletRepository.findFirstByOwner(LedgerPostingService.TREASURY_OWNER)
                .orElseThrow(() -> new IllegalStateException("Treasury wallet is missing — was demo data seeded?"));

        Payment payment = paymentRepository.save(new Payment(
                wallet.getId(), request.amount(), request.currency(), request.cardRef()));
        payment.setProviderRef("SIM-" + HexFormat.of().formatHex(random.generateSeed(8)));
        return payment;
    }
}
