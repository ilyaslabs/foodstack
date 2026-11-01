package io.github.ilyaslabs.foodstack.search.impl;

import java.util.List;

import io.github.ilyaslabs.foodstack.search.api.Fulfilment;
import io.github.ilyaslabs.foodstack.search.api.StoreSearchPage;
import io.github.ilyaslabs.foodstack.search.api.StoreSearchQuery;
import io.github.ilyaslabs.foodstack.search.core.application.StoreSearchService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class SearchApiImplTest {

    private final StoreSearchService service = mock(StoreSearchService.class);

    private final SearchApiImpl api = new SearchApiImpl(this.service);

    @Test
    @DisplayName("delegates store search to the core service")
    void delegates() {
        StoreSearchQuery query = new StoreSearchQuery(31.5, 74.3, "pizza", null, Fulfilment.DELIVERY, 0, 20);
        StoreSearchPage page = new StoreSearchPage(List.of(), List.of(), 0, 0, 20);
        given(this.service.search(query)).willReturn(page);

        assertThat(this.api.searchStores(query)).isEqualTo(page);
    }
}
