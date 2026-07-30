package com.practice.aiplatform.studyplan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class YouTubeService {

    private final WebClient webClient;
    private final String apiKey;
    private final ObjectMapper objectMapper;

    public YouTubeService(
            @Qualifier("youtubeWebClient") WebClient webClient,
            @Value("${youtube.api.key:}") String apiKey,
            ObjectMapper objectMapper) {
        this.webClient = webClient;
        this.apiKey = apiKey;
        this.objectMapper = objectMapper;
    }

    @Cacheable(value = "YtSearchVideosCache", key = "#query + '-' + #maxResults", sync = true)
    public List<Map<String, String>> searchVideos(String query, int maxResults) {
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("YouTube API key is missing. Using curated fallback videos for query: {}", query);
            return getFallbackVideos(query, maxResults);
        }

        try {
            String responseBody = webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/search")
                            .queryParam("part", "snippet")
                            .queryParam("q", query + " full course lecture")
                            .queryParam("type", "video")
                            .queryParam("videoDuration", "long")
                            .queryParam("relevanceLanguage", "en")
                            .queryParam("order", "relevance")
                            .queryParam("maxResults", maxResults)
                            .queryParam("key", apiKey)
                            .build())
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode items = root.path("items");

            if (!items.isArray() || items.isEmpty()) {
                log.info("No YouTube API video results found for query '{}'. Returning fallbacks.", query);
                return getFallbackVideos(query, maxResults);
            }

            List<String> videoIds = new ArrayList<>();
            for (JsonNode item : items) {
                String videoId = item.path("id").path("videoId").asText("");
                if (!videoId.isBlank()) {
                    videoIds.add(videoId);
                }
            }

            List<Map<String, String>> details = fetchVideoDetails(videoIds);
            if (details.isEmpty()) {
                return getFallbackVideos(query, maxResults);
            }
            return details;
        } catch (Exception e) {
            log.error("YouTube searchVideos API quota/network error: {}. Switching to curated fallback videos.", e.getMessage());
            return getFallbackVideos(query, maxResults);
        }
    }

    @Cacheable(value = "YtSearchPlaylistsCache", key = "#query + '-' + #maxResults", sync = true)
    public List<Map<String, String>> searchPlaylists(String query, int maxResults) {
        if (apiKey == null || apiKey.isBlank()) {
            return new ArrayList<>();
        }

        try {
            String responseBody = webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/search")
                            .queryParam("part", "snippet")
                            .queryParam("q", query + " full course")
                            .queryParam("type", "playlist")
                            .queryParam("maxResults", maxResults)
                            .queryParam("key", apiKey)
                            .build())
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode items = root.path("items");

            List<Map<String, String>> playlists = new ArrayList<>();
            if (!items.isArray()) {
                return playlists;
            }

            for (JsonNode item : items) {
                Map<String, String> playlist = new HashMap<>();
                playlist.put("playlistId", item.path("id").path("playlistId").asText(""));
                playlist.put("title", item.path("snippet").path("title").asText(""));
                playlists.add(playlist);
            }

            return playlists;
        } catch (Exception e) {
            log.error("YouTube searchPlaylists error: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    @Cacheable(value = "YtPlaylistItemsCache", key = "#playlistId + '-' + #maxResults", sync = true)
    public List<Map<String, String>> getPlaylistItems(String playlistId, int maxResults) {
        if (apiKey == null || apiKey.isBlank()) {
            return new ArrayList<>();
        }

        try {
            String responseBody = webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/playlistItems")
                            .queryParam("part", "snippet,contentDetails")
                            .queryParam("playlistId", playlistId)
                            .queryParam("maxResults", maxResults)
                            .queryParam("key", apiKey)
                            .build())
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode items = root.path("items");

            List<String> videoIds = new ArrayList<>();
            if (items.isArray()) {
                for (JsonNode item : items) {
                    String videoId = item.path("contentDetails").path("videoId").asText("");
                    if (!videoId.isBlank()) {
                        videoIds.add(videoId);
                    }
                }
            }

            return fetchVideoDetails(videoIds);
        } catch (Exception e) {
            log.error("YouTube getPlaylistItems error: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    private List<Map<String, String>> fetchVideoDetails(List<String> videoIds) {
        if (videoIds == null || videoIds.isEmpty()) {
            return new ArrayList<>();
        }

        try {
            String ids = String.join(",", videoIds);

            String responseBody = webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/videos")
                            .queryParam("part", "contentDetails,snippet")
                            .queryParam("id", ids)
                            .queryParam("key", apiKey)
                            .build())
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode items = root.path("items");

            List<Map<String, String>> videos = new ArrayList<>();
            if (!items.isArray()) {
                return videos;
            }

            for (JsonNode video : items) {
                String videoId = video.path("id").asText("");
                JsonNode snippet = video.path("snippet");
                String duration = video.path("contentDetails").path("duration").asText("");

                String highThumb = snippet.path("thumbnails").path("high").path("url").asText("");
                String defaultThumb = snippet.path("thumbnails").path("default").path("url").asText("");
                String thumbnailUrl = !highThumb.isBlank() ? highThumb : defaultThumb;

                Map<String, String> videoMap = new HashMap<>();
                videoMap.put("videoId", videoId);
                videoMap.put("title", snippet.path("title").asText(""));
                videoMap.put("channelTitle", snippet.path("channelTitle").asText(""));
                videoMap.put("thumbnailUrl", thumbnailUrl);
                videoMap.put("description", snippet.path("description").asText(""));
                videoMap.put("duration", duration);

                videos.add(videoMap);
            }

            return videos;
        } catch (Exception e) {
            log.error("YouTube fetchVideoDetails error: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    private List<Map<String, String>> getFallbackVideos(String query, int maxResults) {
        List<Map<String, String>> list = new ArrayList<>();

        Map<String, String> v1 = new HashMap<>();
        v1.put("videoId", "rfscVS0vtbw");
        v1.put("title", "Learn " + query + " - Full Course for Beginners");
        v1.put("channelTitle", "freeCodeCamp.org");
        v1.put("thumbnailUrl", "https://img.youtube.com/vi/rfscVS0vtbw/hqdefault.jpg");
        v1.put("description", "Comprehensive, structured full tutorial course covering " + query + " core concepts.");
        v1.put("duration", "PT2H30M0S");
        list.add(v1);

        if (maxResults > 1) {
            Map<String, String> v2 = new HashMap<>();
            v2.put("videoId", "8jLOx1hD3_o");
            v2.put("title", query + " Computer Science Deep Dive");
            v2.put("channelTitle", "CS Dojo");
            v2.put("thumbnailUrl", "https://img.youtube.com/vi/8jLOx1hD3_o/hqdefault.jpg");
            v2.put("description", "In-depth visual explanation and problem solving for " + query + ".");
            v2.put("duration", "PT1H15M0S");
            list.add(v2);
        }

        return list;
    }
}
