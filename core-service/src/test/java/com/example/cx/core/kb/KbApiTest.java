package com.example.cx.core.kb;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.cx.core.AbstractApiTest;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

class KbApiTest extends AbstractApiTest {

    private JsonNode article(String token, String title, String body, String visibility, boolean publish) throws Exception {
        MvcResult r = doPost(token, "/api/v1/articles", Map.of("title", title, "summary", "Short summary", "body", body, "visibility", visibility));
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(201);
        JsonNode a = body(r);
        if (publish) {
            MvcResult p = doPost(token, "/api/v1/articles/" + a.get("id").asText() + "/publish", Map.of());
            assertThat(p.getResponse().getStatus()).isEqualTo(200);
            return body(p);
        }
        return a;
    }

    private List<String> titles(String token, String query) throws Exception {
        MvcResult r = doGet(token, "/api/v1/articles?size=100" + query);
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        List<String> out = new ArrayList<>();
        body(r).get("content").forEach(n -> out.add(n.get("title").asText()));
        return out;
    }

    /** Query via .param() so special characters are sent exactly as given (a URL template would double-encode them). */
    private List<String> search(String token, String q) throws Exception {
        MvcResult r = mvc.perform(auth(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/articles"), token)
                .param("q", q).param("size", "100")).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        List<String> out = new ArrayList<>();
        body(r).get("content").forEach(n -> out.add(n.get("title").asText()));
        return out;
    }

    @Test
    void customerSeesOnlyPublishedPublicArticles_visibilityMatrix() throws Exception {
        Tenant t = newTenant();
        String agent = userToken(t, "agent", "AGENT");
        String customer = userToken(t, "cust", "CUSTOMER");
        JsonNode draftPublic = article(agent, "Draft public article", "Body of the draft public article", "PUBLIC", false);
        JsonNode draftInternal = article(agent, "Draft internal article", "Body of the draft internal article", "INTERNAL", false);
        JsonNode pubPublic = article(agent, "Published public article", "Body of the published public article", "PUBLIC", true);
        JsonNode pubInternal = article(agent, "Published internal article", "Body of the published internal article", "INTERNAL", true);

        assertThat(draftPublic.get("status").asText()).isEqualTo("DRAFT");
        assertThat(pubInternal.get("status").asText()).isEqualTo("PUBLISHED");

        // staff see all four, can filter
        assertThat(titles(agent, "")).containsExactlyInAnyOrder("Draft public article", "Draft internal article", "Published public article", "Published internal article");
        assertThat(titles(agent, "&status=DRAFT")).containsExactlyInAnyOrder("Draft public article", "Draft internal article");
        assertThat(titles(agent, "&visibility=INTERNAL&status=PUBLISHED")).containsExactly("Published internal article");

        // customer: PUBLISHED + PUBLIC only; filters can narrow but never widen
        assertThat(titles(customer, "")).containsExactly("Published public article");
        assertThat(titles(customer, "&status=DRAFT")).isEmpty();
        assertThat(titles(customer, "&visibility=INTERNAL")).isEmpty();
        assertThat(titles(customer, "&q=internal")).isEmpty();
        assertThat(titles(customer, "&q=published")).containsExactly("Published public article");

        assertThat(doGet(customer, "/api/v1/articles/" + pubPublic.get("id").asText()).getResponse().getStatus()).isEqualTo(200);
        for (JsonNode hidden : List.of(draftPublic, draftInternal, pubInternal))
            assertThat(doGet(customer, "/api/v1/articles/" + hidden.get("id").asText()).getResponse().getStatus()).as("customer reading " + hidden.get("title")).isEqualTo(404);

        // customers cannot write, read history or link; the customer payload never carries ticket ids
        String id = pubPublic.get("id").asText();
        assertThat(doPost(customer, "/api/v1/articles", Map.of("title", "Nope nope", "body", "Body of nothing at all", "visibility", "PUBLIC")).getResponse().getStatus()).isEqualTo(403);
        assertThat(doPut(customer, "/api/v1/articles/" + id, Map.of("title", "Nope nope", "body", "Body of nothing at all", "visibility", "PUBLIC")).getResponse().getStatus()).isEqualTo(403);
        assertThat(doPost(customer, "/api/v1/articles/" + id + "/publish", Map.of()).getResponse().getStatus()).isEqualTo(403);
        assertThat(doGet(customer, "/api/v1/articles/" + id + "/versions").getResponse().getStatus()).isEqualTo(403);
        assertThat(doPost(customer, "/api/v1/articles/" + id + "/link-ticket/" + UUID.randomUUID(), Map.of()).getResponse().getStatus()).isEqualTo(403);
        assertThat(doPost(customer, "/api/v1/article-categories", Map.of("name", "Sneaky")).getResponse().getStatus()).isEqualTo(403);
        assertThat(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/articles")).andReturn().getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void articlesAreTenantIsolated() throws Exception {
        Tenant a = newTenant(), b = newTenant();
        String agentA = userToken(a, "agent", "AGENT"), agentB = userToken(b, "agent", "AGENT");
        JsonNode art = article(agentA, "Zebrafish migration guide", "Unique body text zebrafish migration steps", "PUBLIC", true);
        String id = art.get("id").asText();
        JsonNode catA = body(doPost(agentA, "/api/v1/article-categories", Map.of("name", "Only in A")));
        JsonNode ticketB = createTicket(agentB, "B ticket", "LOW");

        assertThat(doGet(agentB, "/api/v1/articles/" + id).getResponse().getStatus()).isEqualTo(404);
        assertThat(doPut(agentB, "/api/v1/articles/" + id, Map.of("title", "Hijack title", "body", "Hijacked body text here", "visibility", "PUBLIC")).getResponse().getStatus()).isEqualTo(404);
        assertThat(doPost(agentB, "/api/v1/articles/" + id + "/publish", Map.of()).getResponse().getStatus()).isEqualTo(404);
        assertThat(doGet(agentB, "/api/v1/articles/" + id + "/versions").getResponse().getStatus()).isEqualTo(404);
        assertThat(doPost(agentB, "/api/v1/articles/" + id + "/link-ticket/" + ticketB.get("id").asText(), Map.of()).getResponse().getStatus()).isEqualTo(404);
        assertThat(titles(agentB, "&q=zebrafish")).isEmpty();
        assertThat(titles(agentB, "")).isEmpty();
        // B cannot attach its article to A's category, and A's article cannot be linked to B's ticket
        assertThat(doPost(agentB, "/api/v1/articles", Map.of("title", "Uses foreign category", "body", "Body for the foreign category test", "visibility", "PUBLIC",
                "categoryId", catA.get("id").asText())).getResponse().getStatus()).isEqualTo(400);
        assertThat(doPost(agentA, "/api/v1/articles/" + id + "/link-ticket/" + ticketB.get("id").asText(), Map.of()).getResponse().getStatus()).isEqualTo(404);
        assertThat(body(doGet(agentB, "/api/v1/article-categories")).size()).isZero();
        assertThat(titles(agentA, "&q=zebrafish")).hasSize(1);
        // the original is untouched
        assertThat(body(doGet(agentA, "/api/v1/articles/" + id)).get("title").asText()).isEqualTo("Zebrafish migration guide");
    }

    @Test
    void versionHistoryTracksEveryChangeNewestFirst() throws Exception {
        Tenant t = newTenant();
        String agent = userToken(t, "agent", "AGENT");
        JsonNode created = article(agent, "Original title", "Original body of the article", "INTERNAL", false);
        String id = created.get("id").asText();
        assertThat(created.get("version").asInt()).isEqualTo(1);
        assertThat(created.get("slug").asText()).isEqualTo("original-title");

        MvcResult upd = doPut(agent, "/api/v1/articles/" + id, Map.of("title", "Updated title", "summary", "A summary", "body", "Updated body of the article", "visibility", "PUBLIC", "version", 1));
        assertThat(upd.getResponse().getStatus()).as(upd.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(body(upd).get("version").asInt()).isEqualTo(2);

        // identical content again = no new version
        MvcResult same = doPut(agent, "/api/v1/articles/" + id, Map.of("title", "Updated title", "summary", "A summary", "body", "Updated body of the article", "visibility", "PUBLIC"));
        assertThat(body(same).get("version").asInt()).isEqualTo(2);

        // stale version -> 409
        MvcResult stale = doPut(agent, "/api/v1/articles/" + id, Map.of("title", "Stale write", "body", "Stale body of the article", "visibility", "PUBLIC", "version", 1));
        assertThat(stale.getResponse().getStatus()).isEqualTo(409);
        assertThat(body(stale).get("code").asText()).isEqualTo("CONFLICT");

        MvcResult pub = doPost(agent, "/api/v1/articles/" + id + "/publish", Map.of());
        assertThat(body(pub).get("version").asInt()).isEqualTo(3);
        // publishing again is idempotent (no extra version)
        assertThat(body(doPost(agent, "/api/v1/articles/" + id + "/publish", Map.of())).get("version").asInt()).isEqualTo(3);

        JsonNode versions = body(doGet(agent, "/api/v1/articles/" + id + "/versions"));
        assertThat(versions.size()).isEqualTo(3);
        assertThat(versions.get(0).get("version").asInt()).isEqualTo(3);
        assertThat(versions.get(0).get("status").asText()).isEqualTo("PUBLISHED");
        assertThat(versions.get(1).get("version").asInt()).isEqualTo(2);
        assertThat(versions.get(1).get("title").asText()).isEqualTo("Updated title");
        assertThat(versions.get(1).get("status").asText()).isEqualTo("DRAFT");
        assertThat(versions.get(2).get("version").asInt()).isEqualTo(1);
        assertThat(versions.get(2).get("title").asText()).isEqualTo("Original title");
        assertThat(versions.get(2).get("visibility").asText()).isEqualTo("INTERNAL");
        assertThat(versions.get(2).get("author").get("displayName").asText()).isEqualTo("agent");
        assertThat(body(doGet(agent, "/api/v1/articles/" + id)).get("slug").asText()).as("slug is stable").isEqualTo("original-title");
    }

    @Test
    void searchRanksTitleOverSummaryOverBodyAndIsPaginatedAndSafe() throws Exception {
        Tenant t = newTenant();
        String agent = userToken(t, "agent", "AGENT");
        article(agent, "Quokka onboarding handbook", "General onboarding steps for new joiners and more words", "PUBLIC", true);
        article(agent, "Handbook index", "A list of guides. The quokka procedure is described elsewhere in the body of this one quokka", "PUBLIC", true);
        MvcResult withSummary = doPost(agent, "/api/v1/articles", Map.of("title", "Guide to joiners", "summary", "All about the quokka", "body", "Nothing relevant in this body text really", "visibility", "PUBLIC"));
        doPost(agent, "/api/v1/articles/" + body(withSummary).get("id").asText() + "/publish", Map.of());
        article(agent, "Unrelated topic", "Completely different content about printers", "PUBLIC", true);

        List<String> ranked = titles(agent, "&q=quokka");
        assertThat(ranked).containsExactly("Quokka onboarding handbook", "Guide to joiners", "Handbook index");

        // pagination over the scored list
        MvcResult page0 = doGet(agent, "/api/v1/articles?q=quokka&size=2&page=0");
        MvcResult page1 = doGet(agent, "/api/v1/articles?q=quokka&size=2&page=1");
        assertThat(body(page0).get("totalElements").asInt()).isEqualTo(3);
        assertThat(body(page0).get("totalPages").asInt()).isEqualTo(2);
        assertThat(body(page0).get("content").size()).isEqualTo(2);
        assertThat(body(page1).get("content").size()).isEqualTo(1);
        assertThat(body(page1).get("content").get(0).get("title").asText()).isEqualTo("Handbook index");

        // multiple tokens: title hit on one token + summary hit on the other (5+3+...) beats a single title hit (5 + 10 phrase is absent here)
        assertThat(search(agent, "quokka joiners")).containsExactly("Guide to joiners", "Quokka onboarding handbook", "Handbook index");
        // LIKE wildcards are literals, not wildcards
        assertThat(search(agent, "%")).isEmpty();
        assertThat(search(agent, "'; drop table kb_article;--")).isEmpty();
        assertThat(doGet(agent, "/api/v1/articles?size=0").getResponse().getStatus()).isEqualTo(400);
        assertThat(doGet(agent, "/api/v1/articles?size=101").getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void scorerIsDeterministicAndWeightsFields() {
        var toks = KbSearchScorer.tokens("Quokka handbook");
        String norm = KbSearchScorer.normalise("Quokka handbook");
        int title = KbSearchScorer.score(toks, norm, "Quokka handbook", "", "");
        int summary = KbSearchScorer.score(toks, norm, "x", "quokka handbook", "");
        int body = KbSearchScorer.score(toks, norm, "x", "", "quokka handbook");
        assertThat(title).isGreaterThan(summary);
        assertThat(summary).isGreaterThan(body);
        assertThat(KbSearchScorer.score(toks, norm, "Quokka handbook", "", "")).isEqualTo(title);
        assertThat(KbSearchScorer.tokens("the and how to a")).isEmpty();
    }

    @Test
    void articleTicketLinksAndAudit() throws Exception {
        Tenant t = newTenant();
        String agent = userToken(t, "agent", "AGENT");
        String customer = userToken(t, "cust", "CUSTOMER");
        JsonNode art = article(agent, "Linked article", "Article body that gets linked to a ticket", "PUBLIC", true);
        String id = art.get("id").asText();
        JsonNode ticket = createTicket(customer, "Needs the article", "LOW");

        MvcResult l1 = doPost(agent, "/api/v1/articles/" + id + "/link-ticket/" + ticket.get("id").asText(), Map.of());
        assertThat(l1.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(l1).get("relatedTicketIds").size()).isEqualTo(1);
        MvcResult l2 = doPost(agent, "/api/v1/articles/" + id + "/link-ticket/" + ticket.get("id").asText(), Map.of());
        assertThat(body(l2).get("relatedTicketIds").size()).as("linking twice is idempotent").isEqualTo(1);
        assertThat(doPost(agent, "/api/v1/articles/" + id + "/link-ticket/" + UUID.randomUUID(), Map.of()).getResponse().getStatus()).isEqualTo(404);
        assertThat(body(doGet(agent, "/api/v1/articles/" + id)).get("relatedTicketIds").get(0).asText()).isEqualTo(ticket.get("id").asText());
        assertThat(body(doGet(customer, "/api/v1/articles/" + id)).get("relatedTicketIds").size()).as("customers never see ticket ids").isZero();

        JsonNode audit = body(doGet(t.adminToken(), "/api/v1/audit-events?entityType=KB_ARTICLE&size=50")).get("content");
        List<String> actions = new ArrayList<>();
        audit.forEach(n -> actions.add(n.get("action").asText()));
        assertThat(actions).contains("KB_ARTICLE_CREATED", "KB_ARTICLE_PUBLISHED", "KB_ARTICLE_TICKET_LINKED");
    }

    @Test
    void categoriesAreCachedAndEvictedAndPublishedListStaysFresh() throws Exception {
        Tenant t = newTenant();
        String agent = userToken(t, "agent", "AGENT");
        String customer = userToken(t, "cust", "CUSTOMER");
        assertThat(body(doGet(customer, "/api/v1/article-categories")).size()).isZero();
        MvcResult c = doPost(agent, "/api/v1/article-categories", Map.of("name", "Getting Started"));
        assertThat(c.getResponse().getStatus()).isEqualTo(201);
        assertThat(body(c).get("slug").asText()).isEqualTo("getting-started");
        assertThat(doPost(agent, "/api/v1/article-categories", Map.of("name", "getting started")).getResponse().getStatus()).as("duplicate name").isEqualTo(409);
        JsonNode cats = body(doGet(customer, "/api/v1/article-categories"));
        assertThat(cats.size()).as("cache evicted after the write").isEqualTo(1);

        // warm the customer's cached published list, then publish: the list must reflect it immediately
        assertThat(titles(customer, "")).isEmpty();
        MvcResult a = doPost(agent, "/api/v1/articles", Map.of("title", "Categorised article", "body", "Body of the categorised article", "visibility", "PUBLIC",
                "categoryId", cats.get(0).get("id").asText()));
        assertThat(body(a).get("categoryName").asText()).isEqualTo("Getting Started");
        assertThat(body(a).get("summary").asText()).as("summary is derived from the body when omitted").isEqualTo("Body of the categorised article");
        assertThat(titles(customer, "")).isEmpty();                       // still a draft
        doPost(agent, "/api/v1/articles/" + body(a).get("id").asText() + "/publish", Map.of());
        assertThat(titles(customer, "")).containsExactly("Categorised article");
        assertThat(titles(customer, "&categoryId=" + cats.get(0).get("id").asText())).containsExactly("Categorised article");
        assertThat(titles(customer, "&categoryId=" + UUID.randomUUID())).isEmpty();
    }
}
