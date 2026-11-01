package io.github.ilyaslabs.foodstack.store.impl;

import java.util.List;

import io.github.ilyaslabs.foodstack.store.api.StoreIdPage;
import io.github.ilyaslabs.foodstack.store.api.StoreListingView;
import io.github.ilyaslabs.foodstack.store.core.application.StoreListingService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class StoreApiImplTest {

    private final StoreListingService service = mock(StoreListingService.class);

    private final StoreApiImpl api = new StoreApiImpl(this.service);

    @Test
    @DisplayName("delegates listings and id pages to the core listing service")
    void delegates() {
        StoreListingView listing = mock(StoreListingView.class);
        StoreIdPage page = new StoreIdPage(List.of("s1", "s2"), "s2");
        given(this.service.listing("s1")).willReturn(listing);
        given(this.service.storeIds(null, 2)).willReturn(page);

        assertThat(this.api.getListing("s1")).isEqualTo(listing);
        assertThat(this.api.listStoreIds(null, 2)).isEqualTo(page);
    }
}
