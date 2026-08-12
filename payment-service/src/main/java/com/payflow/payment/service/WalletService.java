package com.payflow.payment.service;

import com.payflow.payment.api.dto.TransactionResponse;
import com.payflow.payment.api.dto.WalletResponse;
import com.payflow.payment.error.ResourceNotFoundException;
import com.payflow.payment.repo.LedgerEntryRepository;
import com.payflow.payment.repo.WalletRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class WalletService {

    private final WalletRepository walletRepository;
    private final LedgerEntryRepository ledgerEntryRepository;

    public WalletService(WalletRepository walletRepository, LedgerEntryRepository ledgerEntryRepository) {
        this.walletRepository = walletRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
    }

    /** Customer wallets only; the internal treasury wallet is not listed. */
    public List<WalletResponse> listWallets() {
        return walletRepository.findByOwnerNotOrderByCreatedAtAsc(PaymentProcessor.TREASURY_OWNER)
                .stream()
                .map(WalletResponse::from)
                .toList();
    }

    public WalletResponse getWallet(UUID id) {
        return walletRepository.findById(id)
                .map(WalletResponse::from)
                .orElseThrow(() -> new ResourceNotFoundException("Wallet", id));
    }

    public List<TransactionResponse> listTransactions(UUID walletId) {
        if (!walletRepository.existsById(walletId)) {
            throw new ResourceNotFoundException("Wallet", walletId);
        }
        return ledgerEntryRepository.findByWalletIdOrderByCreatedAtDesc(walletId)
                .stream()
                .map(TransactionResponse::from)
                .toList();
    }
}
