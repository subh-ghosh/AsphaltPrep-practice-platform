package com.practice.aiplatform.user;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

@Service
public class GitHubAuthService {

    @Value("${github.client.id:}")
    private String clientId;

    @Value("${github.client.secret:}")
    private String clientSecret;

    private final RestTemplate restTemplate = new RestTemplate();

    public record GitHubUser(
            Long id,
            String login,
            String name,
            String email,
            String avatarUrl,
            String htmlUrl
    ) {}

    public GitHubUser processGitHubCode(String code) {
        if (code == null || code.isBlank()) {
            throw new RuntimeException("Authorization code cannot be null or empty");
        }

        // 1. Exchange code for access token
        String tokenUrl = "https://github.com/login/oauth/access_token";
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, String> tokenRequestBody = Map.of(
                "client_id", clientId,
                "client_secret", clientSecret,
                "code", code
        );

        HttpEntity<Map<String, String>> tokenRequest = new HttpEntity<>(tokenRequestBody, headers);
        ResponseEntity<Map> tokenResponse = restTemplate.postForEntity(tokenUrl, tokenRequest, Map.class);

        if (tokenResponse.getBody() == null || !tokenResponse.getBody().containsKey("access_token")) {
            String errorMsg = tokenResponse.getBody() != null && tokenResponse.getBody().containsKey("error_description")
                    ? (String) tokenResponse.getBody().get("error_description")
                    : "Failed to obtain access token from GitHub";
            throw new RuntimeException(errorMsg);
        }

        String accessToken = (String) tokenResponse.getBody().get("access_token");

        // 2. Fetch User Profile
        HttpHeaders userHeaders = new HttpHeaders();
        userHeaders.setBearerAuth(accessToken);
        userHeaders.setAccept(List.of(MediaType.APPLICATION_JSON));
        HttpEntity<Void> userEntity = new HttpEntity<>(userHeaders);

        ResponseEntity<Map> userResponse = restTemplate.exchange(
                "https://api.github.com/user",
                HttpMethod.GET,
                userEntity,
                Map.class
        );

        Map userBody = userResponse.getBody();
        if (userBody == null) {
            throw new RuntimeException("Failed to fetch user profile from GitHub");
        }

        Long id = ((Number) userBody.get("id")).longValue();
        String login = (String) userBody.get("login");
        String name = (String) userBody.get("name");
        String email = (String) userBody.get("email");
        String avatarUrl = (String) userBody.get("avatar_url");
        String htmlUrl = (String) userBody.get("html_url");

        // 3. Fallback: If email is private/null, fetch from /user/emails endpoint
        if (email == null || email.isBlank()) {
            try {
                ResponseEntity<List> emailsResponse = restTemplate.exchange(
                        "https://api.github.com/user/emails",
                        HttpMethod.GET,
                        userEntity,
                        List.class
                );

                List<Map<String, Object>> emails = emailsResponse.getBody();
                if (emails != null) {
                    for (Map<String, Object> emailObj : emails) {
                        Boolean primary = (Boolean) emailObj.get("primary");
                        Boolean verified = (Boolean) emailObj.get("verified");
                        if (Boolean.TRUE.equals(primary) && Boolean.TRUE.equals(verified)) {
                            email = (String) emailObj.get("email");
                            break;
                        }
                    }
                    if ((email == null || email.isBlank()) && !emails.isEmpty()) {
                        email = (String) emails.get(0).get("email");
                    }
                }
            } catch (Exception ignored) {
                // If email API fails, fallback to pseudo-email using GitHub login handle
            }
        }

        if (email == null || email.isBlank()) {
            email = login + "@users.noreply.github.com";
        }

        return new GitHubUser(id, login, name, email, avatarUrl, htmlUrl);
    }
}
