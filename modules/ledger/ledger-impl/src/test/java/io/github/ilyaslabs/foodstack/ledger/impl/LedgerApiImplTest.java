package io.github.ilyaslabs.foodstack.ledger.impl;

import io.github.ilyaslabs.foodstack.ledger.api.StoreBalance;
import io.github.ilyaslabs.foodstack.ledger.core.application.LedgerReports;
import io.github.ilyaslabs.moduleswitch.ModuleException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class LedgerApiImplTest {

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("a branch's balance is for the platform and finance only; owed to the operator when negative")
    void storeBalance() {
        LedgerReports reports = mock(LedgerReports.class);
        given(reports.storeBalance("s1")).willReturn(new StoreBalance("s1", -2_500, "PKR"));
        LedgerApiImpl api = new LedgerApiImpl(reports);

        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("u", "p", "store:portal"));
        assertThatThrownBy(() -> api.storeBalance("s1")).isInstanceOf(ModuleException.Forbidden.class);

        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("svc", "p", "module:service"));
        StoreBalance balance = api.storeBalance("s1");
        assertThat(balance.balanceMinor()).isEqualTo(-2_500);
        assertThat(balance.owedToOperatorMinor()).isEqualTo(2_500);
        assertThat(balance.currency()).isEqualTo("PKR");
    }
}
