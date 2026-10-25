package com.example.cx.core.kb;

import com.example.cx.core.tickets.Visibility;
import java.util.UUID;

/** Published article summary handed to the AI recommender (no body, so the payload stays small and bounded). */
public record KbCandidate(UUID id, String title, String summary, Visibility visibility, UUID categoryId) {}
