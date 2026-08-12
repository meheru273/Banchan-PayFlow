package com.payflow.payment.service;

import com.payflow.common.domain.Payment;
import com.payflow.common.domain.Wallet;
import com.payflow.common.ledger.LedgerPostingService;
import com.payflow.common.repo.PaymentRepository;
import com.payflow.common.repo.WalletRepository;
import com.payflow.payment.api.dto.CreatePaymentRequest;
import com.payflow.payment.api.dto.PaymentResponse;
import com.payflow.payment.error.DomainValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentProcessorTest {

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private WalletRepository walletRepository;

    @Mock
    private LedgerPostingService ledgerPostingService;

    private PaymentProcessor processor;

    private Wallet customer;
    private final UUID customerId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        processor = new PaymentProcessor(paymentRepository, walletRepository, ledgerPostingService);
        customer = new Wallet("Jisoo Kim", "GBP", new BigDecimal("10.00"));
        lenient().when(walletRepository.findById(customerId)).thenReturn(Optional.of(customer));
        lenient().when(walletRepository.findFirstByOwner(LedgerPostingService.TREASURY_OWNER))
                .thenReturn(Optional.of(new Wallet(LedgerPostingService.TREASURY_OWNER, "GBP", BigDecimal.TEN)));
        lenient().when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void createsAPendingPaymentWithAProviderRef() {
        PaymentResponse response = processor.process(
                new CreatePaymentRequest(customerId, new BigDecimal("42.50"), "GBP", "tok_visa_4242"));

        assertThat(response.status()).isEqualTo("PENDING");
        assertThat(response.providerRef()).startsWith("SIM-");
        // Completion is asynchronous: creation must not touch the ledger.
        org.mockito.Mockito.verifyNoInteractions(ledgerPostingService);
    }

    @Test
    void rejectsUnknownWallet() {
        UUID missing = UUID.randomUUID();
        when(walletRepository.findById(missing)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> processor.process(
                new CreatePaymentRequest(missing, new BigDecimal("1.00"), "GBP", null)))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("does not exist");
    }

    @Test
    void rejectsCurrencyMismatch() {
        assertThatThrownBy(() -> processor.process(
                new CreatePaymentRequest(customerId, new BigDecimal("1.00"), "USD", null)))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("does not match wallet currency");
    }
}
