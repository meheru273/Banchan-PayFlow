package com.payflow.common.ledger;

import com.payflow.common.domain.LedgerDirection;
import com.payflow.common.domain.LedgerEntry;
import com.payflow.common.domain.Payment;
import com.payflow.common.domain.PaymentStatus;
import com.payflow.common.domain.Wallet;
import com.payflow.common.repo.LedgerEntryRepository;
import com.payflow.common.repo.PaymentRepository;
import com.payflow.common.repo.WalletRepository;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LedgerPostingServiceTest {

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private WalletRepository walletRepository;

    @Mock
    private LedgerEntryRepository ledgerEntryRepository;

    @Captor
    private ArgumentCaptor<List<LedgerEntry>> entriesCaptor;

    private LedgerPostingService service;

    private final UUID paymentId = UUID.randomUUID();
    private Payment payment;
    private Wallet customer;
    private Wallet treasury;

    @BeforeEach
    void setUp() {
        service = new LedgerPostingService(paymentRepository, walletRepository, ledgerEntryRepository);
        customer = new Wallet("Jisoo Kim", "GBP", new BigDecimal("10.00"));
        treasury = new Wallet(LedgerPostingService.TREASURY_OWNER, "GBP", new BigDecimal("1000.00"));
        payment = new Payment(UUID.randomUUID(), new BigDecimal("42.50"), "GBP", null);
        lenient().when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(payment));
        lenient().when(walletRepository.findById(payment.getWalletId())).thenReturn(Optional.of(customer));
        lenient().when(walletRepository.findFirstByOwner(LedgerPostingService.TREASURY_OWNER))
                .thenReturn(Optional.of(treasury));
        lenient().when(ledgerEntryRepository.existsByPaymentId(paymentId)).thenReturn(false);
        lenient().when(ledgerEntryRepository.signedSumForPayment(paymentId)).thenReturn(new BigDecimal("0.00"));
    }

    @Test
    void postsABalancedPairAndCompletesThePayment() {
        service.postForPayment(paymentId);

        verify(ledgerEntryRepository).saveAll(entriesCaptor.capture());
        List<LedgerEntry> entries = entriesCaptor.getValue();
        assertThat(entries).hasSize(2);

        LedgerEntry debit = entries.stream()
                .filter(e -> e.getDirection() == LedgerDirection.DEBIT).findFirst().orElseThrow();
        LedgerEntry credit = entries.stream()
                .filter(e -> e.getDirection() == LedgerDirection.CREDIT).findFirst().orElseThrow();
        assertThat(debit.getAmount()).isEqualByComparingTo(credit.getAmount());
        assertThat(credit.getAmount()).isEqualByComparingTo(new BigDecimal("42.50"));

        assertThat(customer.getBalance()).isEqualByComparingTo(new BigDecimal("52.50"));
        assertThat(treasury.getBalance()).isEqualByComparingTo(new BigDecimal("957.50"));
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
    }

    @Test
    void skipsAnAlreadyCompletedPayment() {
        payment.setStatus(PaymentStatus.COMPLETED);

        service.postForPayment(paymentId);

        verify(ledgerEntryRepository, never()).saveAll(any());
        assertThat(customer.getBalance()).isEqualByComparingTo(new BigDecimal("10.00"));
    }

    @Test
    void redeliveredEventWithExistingEntriesOnlyMarksCompleted() {
        when(ledgerEntryRepository.existsByPaymentId(paymentId)).thenReturn(true);

        service.postForPayment(paymentId);

        verify(ledgerEntryRepository, never()).saveAll(any());
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(customer.getBalance()).isEqualByComparingTo(new BigDecimal("10.00"));
    }
}
