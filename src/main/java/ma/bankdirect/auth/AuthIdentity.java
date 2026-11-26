package ma.bankdirect.auth;

/** Public response DTOs deliberately exclude password hashes and confidential customer fields. */
public record AuthIdentity(PublicUser user, PublicCustomer customer, boolean mustChangePassword) {
    public record PublicUser(long id, String username, String role) {}
    public record PublicCustomer(long id, String firstName, String lastName) {}
    public record LoginResponse(String token, String expiresAt, AuthIdentity identity) {}
}
