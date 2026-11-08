package io.github.ilyaslabs.foodstack.identity.core.application;

import io.github.ilyaslabs.foodstack.identity.core.IdentityProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class BootstrapAdminTest {

    private final UserService users = mock(UserService.class);

    private final IdentityProperties properties = new IdentityProperties();

    @Test
    @DisplayName("a personal-data key that cannot read the stored accounts stops the start, before any account is made")
    void wrongKeyStopsTheStart() {
        // given
        this.properties.getBootstrapAdmin().setEmail("admin@example.com");
        this.properties.getBootstrapAdmin().setPassword("first password");
        given(this.users.piiKeyOpensStoredData()).willReturn(false);

        // then
        assertThatIllegalStateException().isThrownBy(() -> new BootstrapAdmin(this.users, this.properties).createIfMissing())
            .withMessageContaining("FOODSTACK_PII_KEY");
        verify(this.users, never()).emailExists(anyString());
        verify(this.users, never()).create(any(), anyString());
    }
}
