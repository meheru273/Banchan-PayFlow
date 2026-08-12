package com.payflow.payment.service;

import com.payflow.payment.api.dto.CreatePaymentRequest;
import com.payflow.payment.api.dto.PaymentResponse;
import com.payflow.payment.domain.LedgerDirection;
import com.payflow.payment.domain.LedgerEntry;
import com.payflow.payment.domain.Payment;
import com.payflow.payment.domain.Wallet;
import com.payflow.payment.error.DomainValidationException;
import com.payflow.payment.repo.LedgerEntryRepository;
import com.payflow.payment.repo.PaymentRepository;
import com.payflow.payment.repo.WalletRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
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
    private LedgerEntryRepository ledgerEntryRepository;

    @Captor
    private ArgumentCaptor<List<LedgerEntry>> entriesCaptor;

    private PaymentProcessor processor;

    private Wallet customer;
    private Wallet treasury;
    private final UUID customerId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        processor = new PaymentProcessor(paymentRepository, walletRepository, ledgerEntryRepository);
        customer = new Wallet("Jisoo Kim", "GBP", new BigDecimal("10.00"));
        treasury = new Wallet(PaymentProcessor.TREASURY_OWNER, "GBP", new BigDecimal("1000.00"));
        lenient().when(walletRepository.findById(customerId)).thenReturn(Optional.of(customer));
        lenient().when(walletRepository.findFirstByOwner(PaymentProcessor.TREASURY_OWNER))
                .thenReturn(Optional.of(treasury));
        lenient().when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(ledgerEntryRepository.signedSumForPayment(any())).thenReturn(new BigDecimal("0.00"));
    }

    @Test
    void postsABalancedDebitCreditPairAndUpdatesBalances() {
        BigDecimal amount = new BigDecimal("42.50");

        PaymentResponse response = processor.process(
                new CreatePaymentRequest(customerId, amount, "GBP", "tok_visa_4242"));

        assertThat(response.status()).isEqualTo("COMPLETED");
        assertThat(response.providerRef()).startsWith("SIM-");

        org.mockito.Mockito.verify(ledgerEntryRepository).saveAll(entriesCaptor.capture());
        List<LedgerEntry> entries = entriesCaptor.getValue();
        assertThat(entries).hasSize(2);

        LedgerEntry debit = entries.stream()
                .filter(e -> e.getDirection() == LedgerDirection.DEBIT).findFirst().orElseThrow();
        LedgerEntry credit = entries.stream()
                .filter(e -> e.getDirection() == LedgerDirection.CREDIT).findFirst().orElseThrow();

        assertThat(debit.getAmount()).isEqualByComparingTo(credit.getAmount());
        assertThat(credit.getAmount()).isEqualByComparingTo(amount);
        assertThat(debit.getWalletId()).isEqualTo(treasury.getId());
        assertThat(credit.getWalletId()).isEqualTo(customer.getId());

        assertThat(customer.getBalance()).isEqualByComparingTo(new BigDecimal("52.50"));
        assertThat(treasury.getBalance()).isEqualByComparingTo(new BigDecimal("957.50"));
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
