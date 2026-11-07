package io.github.ilyaslabs.foodstack.notification.core.application;

import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import io.github.ilyaslabs.foodstack.identity.api.IdentityApi;
import io.github.ilyaslabs.foodstack.identity.api.Role;
import io.github.ilyaslabs.foodstack.ledger.api.events.StoreCreditLimitExceeded;
import io.github.ilyaslabs.foodstack.ledger.api.events.StoreCreditRestored;
import io.github.ilyaslabs.foodstack.notification.core.application.NotificationService.Message;
import io.github.ilyaslabs.foodstack.platform.security.ServiceCalls;
import io.github.ilyaslabs.foodstack.settings.api.LocaleView;
import io.github.ilyaslabs.foodstack.settings.api.SettingsApi;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class FinanceMessagesTest {

    private final NotificationService notifications = mock(NotificationService.class);

    private final SettingsApi settings = mock(SettingsApi.class);

    private final IdentityApi identity = mock(IdentityApi.class);

    private final ServiceCalls serviceCalls = mock(ServiceCalls.class);

    private final FinanceMessages messages = new FinanceMessages(this.notifications, this.settings, this.identity,
            this.serviceCalls);

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        given(this.settings.getLocale()).willReturn(new LocaleView("en", List.of("en", "ur"), "PKR", "Asia/Karachi", 1));
        given(this.serviceCalls.call(any())).willAnswer((invocation) -> ((Supplier<Object>) invocation.getArgument(0)).get());
    }

    @Test
    @DisplayName("going over the credit limit tells the owners and finance by email too; getting back tells the owners")
    void creditLimit() {
        given(this.identity.userIdsWithRoles(Set.of(Role.ADMIN, Role.FINANCE))).willReturn(List.of("fin-1", "owner-1"));

        this.messages.onExceeded("e1", new StoreCreditLimitExceeded("s1", "b1", "Napoli · DHA", Set.of("owner-1"), 1_250_000,
                1_000_000, "PKR"));
        ArgumentCaptor<Message> sent = ArgumentCaptor.forClass(Message.class);
        verify(this.notifications, times(2)).send(sent.capture());
        assertThat(sent.getAllValues()).extracting(Message::userId).containsExactly("owner-1", "fin-1");
        Message first = sent.getAllValues().get(0);
        assertThat(first.template()).isEqualTo("credit-limit-exceeded");
        assertThat(first.email()).isTrue();
        assertThat(first.link()).isEqualTo(FinanceMessages.FINANCE_LINK);
        assertThat(first.params().get("store")).isEqualTo("Napoli · DHA");

        this.messages.onRestored("e2", new StoreCreditRestored("s1", "b1", "Napoli · DHA", Set.of("owner-1"), 0, 1_000_000,
                "PKR"));
        verify(this.notifications, times(3)).send(sent.capture());
        Message back = sent.getValue();
        assertThat(back.template()).isEqualTo("credit-limit-restored");
        assertThat(back.email()).isFalse();
    }
}
