package io.github.ilyaslabs.foodstack.notification.core.channel;

/** Sends one email. Implementations throw {@link MailFailure} when it did not go out. */
public interface MailTransport {

    void send(String to, String subject, String html, String text);

    /**
     * An email that did not go out.
     */
    class MailFailure extends RuntimeException {

        private final boolean permanent;

        public MailFailure(String message, boolean permanent, Throwable cause) {
            super(message, cause);
            this.permanent = permanent;
        }

        /** Whether trying again cannot help, such as an invalid address. */
        public boolean isPermanent() {
            return this.permanent;
        }
    }
}
