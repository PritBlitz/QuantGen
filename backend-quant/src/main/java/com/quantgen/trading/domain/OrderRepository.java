package com.quantgen.trading.domain;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderRepository extends JpaRepository<Order, UUID> {

    Page<Order> findByUserIdOrderByCreatedAtDescIdDesc(UUID userId, Pageable pageable);

    long countByStatusAndCreatedAtGreaterThanEqual(OrderStatus status, Instant since);
}
