package com.example.cx.core.ai;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * EXTENSION POINT: a source of ticket-assist suggestions. Implementations are PURE with respect to the platform: they receive
 * already sanitised text plus bounded candidate lists, return data, and have no access to the database, tenant context or any
 * action API. The service validates every field of the result against the candidates before it is stored.
 *
 * Implementations: {@code MockRuleBasedAiProvider} (default, deterministic keyword rules - NOT machine learning) and
 * {@code HttpAiProvider} (optional, selected with AI_PROVIDER=http; never required).
 */
public interface AiProvider {
    String name();

    AiResult analyze(AiRequest request) throws AiProviderException;

    record CategoryOption(UUID id, String name) {}

    /** {@code publicArticle}: only PUBLIC articles may be cited in a customer-facing reply. */
    record ArticleOption(UUID id, String title, String summary, boolean publicArticle) {}

    /** All text is already sanitised/redacted and length-bounded. */
    record AiRequest(String title, String description, String currentCategoryName, List<CategoryOption> categories,
                     List<ArticleOption> articles) {}

    record ScoredArticle(UUID id, String title, double score) {}

    record AiResult(String summary, String categoryName, String priority, double confidence, String explanation,
                    List<ScoredArticle> articles, String reply, Map<String, Object> features) {}
}
