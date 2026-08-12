package com.payflow.payment.service;

import com.payflow.payment.api.dto.CreatePaymentRequest;
import com.payflow.payment.api.dto.WalletResponse;
import com.payflow.payment.domain.Wallet;
import com.payflow.payment.repo.IdempotencyRecordRepository;
import com.payflow.payment.repo.LedgerEntryRepository;
import com.payflow.payment.repo.PaymentRepository;
import com.payflow.payment.repo.WalletRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Service
public class DemoDataService {

    private static final Logger log = LoggerFactory.getLogger(DemoDataService.class);

    private final WalletRepository walletRepository;
    private final PaymentRepository paymentRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final IdempotencyRecordRepository idempotencyRecordRepository;
    private final PaymentProcessor paymentProcessor;

    public DemoDataService(WalletRepository walletRepository,
                           PaymentRepository paymentRepository,
                           LedgerEntryRepository ledgerEntryRepository,
                           IdempotencyRecordRepository idempotencyRecordRepository,
                           PaymentProcessor paymentProcessor) {
        this.walletRepository = walletRepository;
        this.paymentRepository = paymentRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.idempotencyRecordRepository = idempotencyRecordRepository;
        this.paymentProcessor = paymentProcessor;
    }

    @Transactional
    public void seedIfEmpty() {
        if (walletRepository.count() > 0) {
            return;
        }
        seed();
    }

    @Transactional
    public List<WalletResponse> reset() {
        ledgerEntryRepository.deleteAllInBatch();
        paymentRepository.deleteAllInBatch();
        idempotencyRecordRepository.deleteAllInBatch();
        walletRepository.deleteAllInBatch();
        return seed();
    }

    private List<WalletResponse> seed() {
        walletRepository.save(new Wallet(
                PaymentProcessor.TREASURY_OWNER, "GBP", new BigDecimal("1000000.00")));
        Wallet jisoo = walletRepository.save(new Wallet("Jisoo Kim", "GBP", BigDecimal.ZERO));
        Wallet minho = walletRepository.save(new Wallet("Minho Park", "GBP", BigDecimal.ZERO));

        // Seed through the real code path so demo data obeys the same invariants.
        paymentProcessor.process(new CreatePaymentRequest(
                jisoo.getId(), new BigDecimal("42.50"), "GBP", "tok_visa_4242"));
        paymentProcessor.process(new CreatePaymentRequest(
                jisoo.getId(), new BigDecimal("18.90"), "GBP", "tok_visa_4242"));
        paymentProcessor.process(new CreatePaymentRequest(
                minho.getId(), new BigDecimal("77.00"), "GBP", "tok_mc_5100"));

        log.info("Seeded demo data: 2 customer wallets, 3 completed payments");
        return List.of(WalletResponse.from(jisoo), WalletResponse.from(minho));
    }
}
