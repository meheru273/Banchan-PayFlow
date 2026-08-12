package com.payflow.common.repo;

import com.payflow.common.domain.Wallet;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WalletRepository extends JpaRepository<Wallet, UUID> {

    Optional<Wallet> findFirstByOwner(String owner);

    List<Wallet> findByOwnerNotOrderByCreatedAtAsc(String owner);
}
