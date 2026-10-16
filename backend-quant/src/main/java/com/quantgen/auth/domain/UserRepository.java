package com.quantgen.auth.domain;

import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface UserRepository extends JpaRepository<User, UUID> {

    boolean existsByEmail(String email);

    Optional<User> findByEmail(String email);

    long countByRole(Role role);

    Page<User> findAllByOrderByCreatedAtDescIdDesc(Pageable pageable);

    /** {@code pattern} is a lower-case LIKE pattern with '\' as the escape character. */
    @Query("""
            select u from User u
             where lower(u.fullName) like :pattern escape '\\'
                or u.email like :pattern escape '\\'
                or lower(u.phone) like :pattern escape '\\'
             order by u.createdAt desc, u.id desc
            """)
    Page<User> search(String pattern, Pageable pageable);

    /** Locks the user row so concurrent failed logins are counted exactly. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.email = :email")
    Optional<User> findByEmailForUpdate(String email);
}
