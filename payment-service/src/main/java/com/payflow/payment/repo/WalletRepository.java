package com.payflow.payment.repo;

import com.payflow.payment.domain.Wallet;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WalletRepository extends JpaRepository<Wallet, UUID> {

    Optional<Wallet> findFirstByOwner(String owner);

    List<Wallet> findByOwnerNotOrderByCreatedAtAsc(String owner);
}
