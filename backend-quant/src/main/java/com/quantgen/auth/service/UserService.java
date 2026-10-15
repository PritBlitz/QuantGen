package com.quantgen.auth.service;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.quantgen.auth.api.AuthDtos.UserDto;
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
}
