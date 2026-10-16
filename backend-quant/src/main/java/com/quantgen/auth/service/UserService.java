package com.quantgen.auth.service;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.quantgen.auth.api.AuthDtos.UserDto;
import com.quantgen.auth.domain.Role;
import com.quantgen.auth.domain.UserRepository;
import com.quantgen.common.error.ApiException;

/** Read access to users for the auth API and for other modules. */
@Service
public class UserService {

    private final UserRepository users;

    public UserService(UserRepository users) {
        this.users = users;
    }

    @Transactional(readOnly = true)
    public Optional<UserDto> findUser(UUID id) {
        return users.findById(id).map(AuthService::toDto);
    }

    @Transactional(readOnly = true)
    public UserDto getUser(UUID id) {
        return findUser(id).orElseThrow(() -> ApiException.notFound("User"));
    }

    /** Users by id (missing ids are omitted). */
    @Transactional(readOnly = true)
    public Map<UUID, UserDto> findUsers(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return users.findAllById(ids).stream().map(AuthService::toDto)
                .collect(Collectors.toMap(UserDto::id, Function.identity()));
    }

    /**
     * Admin user search, newest first. {@code q} matches name, email or phone, case-insensitively, as a
     * substring; LIKE wildcards in it are matched literally.
     */
    @Transactional(readOnly = true)
    public Page<UserDto> search(String q, Pageable pageable) {
        String term = q == null ? "" : q.trim().toLowerCase();
        if (term.isEmpty()) {
            return users.findAllByOrderByCreatedAtDescIdDesc(pageable).map(AuthService::toDto);
        }
        String pattern = "%" + term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        return users.search(pattern, pageable).map(AuthService::toDto);
    }

    @Transactional(readOnly = true)
    public long countCustomers() {
        return users.countByRole(Role.CUSTOMER);
    }
}
