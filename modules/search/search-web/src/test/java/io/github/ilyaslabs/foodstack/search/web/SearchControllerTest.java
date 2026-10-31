package io.github.ilyaslabs.foodstack.search.web;

import java.util.List;
import java.util.Map;

import io.github.ilyaslabs.foodstack.platform.security.ApiSecurityConfiguration;
import io.github.ilyaslabs.foodstack.platform.web.ProblemDetailsAdvice;
import io.github.ilyaslabs.foodstack.search.api.CuisineCount;
import io.github.ilyaslabs.foodstack.search.api.Fulfilment;
import io.github.ilyaslabs.foodstack.search.api.StoreCard;
import io.github.ilyaslabs.foodstack.search.api.StoreSearchPage;
import io.github.ilyaslabs.foodstack.search.api.StoreSearchQuery;
import io.github.ilyaslabs.foodstack.search.core.application.StoreIndexer;
import io.github.ilyaslabs.foodstack.search.core.application.StoreSearchService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = SearchController.class, properties = "module-switch.modules.search.mode=local")
@Import({ ApiSecurityConfiguration.class, ProblemDetailsAdvice.class, SearchWebConfiguration.class })
class SearchControllerTest {

    @SpringBootApplication
    static class App {
    }

    @MockitoBean
    StoreSearchService searchService;

    @MockitoBean
    StoreIndexer indexer;

    @MockitoBean
    JwtDecoder jwtDecoder;

    @Autowired
    MockMvc mvc;

    @Test
    @DisplayName("store search is public and passes the customer's point, text, cuisine and fulfilment")
    void search() throws Exception {
        given(this.searchService.search(new StoreSearchQuery(31.5, 74.3, "pizza", "italian", Fulfilment.PICKUP, 1, 10)))
            .willReturn(new StoreSearchPage(List.of(new StoreCard("s1", "b1", "Napoli Pizza", "DHA", 1, Map.of(),
                    List.of("italian"), Map.of(), Map.of(), "DHA", "Lahore", 1900, "SELF", true, true, false, 20, List.of())),
                    List.of(new CuisineCount("italian", 1)), 1, 1, 10));

        this.mvc.perform(get("/api/v1/search/stores").param("lat", "31.5").param("lng", "74.3").param("q", "pizza")
            .param("cuisine", "italian").param("fulfilment", "pickup").param("page", "1").param("size", "10"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.stores[0].storeId").value("s1"))
            .andExpect(jsonPath("$.stores[0].openNow").value(true))
            .andExpect(jsonPath("$.cuisines[0].cuisine").value("italian"));
        this.mvc.perform(get("/api/v1/search/stores").param("lat", "31.5").param("lng", "74.3").param("fulfilment", "drone"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("SEARCH_INVALID"));
    }

    @Test
    @DisplayName("only search:manage may start a rebuild")
    void reindex() throws Exception {
        this.mvc.perform(post("/api/v1/search/reindex")
            .with(jwt().authorities(List.of(new SimpleGrantedAuthority("search:manage")))))
            .andExpect(status().isAccepted());
        verify(this.indexer, timeout(2000)).rebuild();
        this.mvc.perform(post("/api/v1/search/reindex").with(jwt().authorities(List.of(new SimpleGrantedAuthority("store:portal")))))
            .andExpect(status().isForbidden());
        this.mvc.perform(post("/api/v1/search/reindex")).andExpect(status().isUnauthorized());
    }
}
