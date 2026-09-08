package com.tpross.service;

import com.tpross.dto.CreateUserRequest;
import com.tpross.dto.UserResponse;
import com.tpross.exception.DuplicateEmailException;
import com.tpross.model.User;
import com.tpross.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserRepository users, PasswordEncoder passwordEncoder) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public UserResponse createUser(CreateUserRequest request) {
        if (users.existsByEmailIgnoreCase(request.email())) {
            throw new DuplicateEmailException();
        }
        User user = users.saveAndFlush(new User(request.email(), passwordEncoder.encode(request.password())));
        return new UserResponse(user.getId(), user.getEmail(), user.getCreatedAt());
    }
}
