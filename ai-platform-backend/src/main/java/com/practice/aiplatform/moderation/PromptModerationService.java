package com.practice.aiplatform.moderation;

import com.practice.aiplatform.ai.AiService;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class PromptModerationService {

    private final AiService aiService;

    public PromptModerationService(AiService aiService) {
        this.aiService = aiService;
    }

    public boolean isBlocked(String... parts) {
        String text = join(parts);
        if (text.isBlank()) {
            return false;
        }

        // Fast-path: Standard benign topic/subject inputs under 100 characters without profanity/suspicious patterns
        if (text.length() < 100 && !isSuspicious(text)) {
            return false;
        }

        String moderationPrompt = """
                You are a strict content safety checker.
                Check whether this user input contains vulgar, abusive, hateful, sexual, self-harm, or violent harassment language.
                Respond with exactly one token: ALLOW or BLOCK.

                User input:
                """ + text;

        try {
            String result = aiService.generatePracticeContent(moderationPrompt);
            String normalized = result == null ? "" : result.trim().toUpperCase();
            return normalized.startsWith("BLOCK");
        } catch (Exception ex) {
            // Fail-open to avoid blocking valid users on moderation service outages.
            return false;
        }
    }

    private boolean isSuspicious(String text) {
        String lower = text.toLowerCase();
        return lower.contains("vulgar") || lower.contains("abuse") || lower.contains("fuck")
                || lower.contains("shit") || lower.contains("kill") || lower.contains("sex")
                || lower.contains("nude") || lower.contains("porn") || lower.contains("hack")
                || lower.contains("exploit") || lower.contains("malware");
    }

    public String warningMessage() {
        return "Input contains unsafe language. Please revise and try again.";
    }

    public boolean isBlockedFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return false;
        }

        try {
            String mimeType = file.getContentType();
            byte[] data = file.getBytes();
            String content = "";

            if (mimeType != null && mimeType.startsWith("text/")) {
                content = new String(data);
            } else if ("application/pdf".equalsIgnoreCase(mimeType)) {
                try (PDDocument document = Loader.loadPDF(data)) {
                    PDFTextStripper stripper = new PDFTextStripper();
                    content = stripper.getText(document);
                }
            }

            if (content == null || content.isBlank()) {
                return false;
            }

            // Keep cost bounded.
            if (content.length() > 6000) {
                content = content.substring(0, 6000);
            }

            return isBlocked(content);
        } catch (Exception ex) {
            // Fail-open on parsing errors.
            return false;
        }
    }

    private String join(String... parts) {
        StringBuilder sb = new StringBuilder();
        if (parts == null) {
            return "";
        }
        for (String part : parts) {
            if (part == null || part.isBlank()) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append('\n');
            }
            sb.append(part.trim());
        }
        return sb.toString();
    }
}
