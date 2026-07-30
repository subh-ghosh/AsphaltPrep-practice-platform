package com.practice.aiplatform.user;

public record RegisterRequest(
    String firstName,
    String lastName,
    String email,
    String password,
    String avatarUrl,
    String githubUrl
) {}
