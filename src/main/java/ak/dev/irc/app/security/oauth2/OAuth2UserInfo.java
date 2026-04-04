package ak.dev.irc.app.security.oauth2;

import lombok.Getter;

import java.util.Map;

/**
 * Abstracts user info extraction from different OAuth2 providers.
 * <p>Currently supports Google. Add more providers by extending this class.</p>
 */
@Getter
public abstract class OAuth2UserInfo {

    protected Map<String, Object> attributes;

    protected OAuth2UserInfo(Map<String, Object> attributes) {
        this.attributes = attributes;
    }

    public abstract String getId();
    public abstract String getName();
    public abstract String getEmail();
    public abstract String getImageUrl();
    public abstract String getFirstName();
    public abstract String getLastName();

    // ══════════════════════════════════════════════════════════════════════════
    //  FACTORY
    // ══════════════════════════════════════════════════════════════════════════

    public static OAuth2UserInfo of(String registrationId, Map<String, Object> attributes) {
        if ("google".equalsIgnoreCase(registrationId)) {
            return new GoogleOAuth2UserInfo(attributes);
        }
        throw new IllegalArgumentException(
                "Unsupported OAuth2 provider: " + registrationId);
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  GOOGLE
    // ══════════════════════════════════════════════════════════════════════════

    public static class GoogleOAuth2UserInfo extends OAuth2UserInfo {

        public GoogleOAuth2UserInfo(Map<String, Object> attributes) {
            super(attributes);
        }

        @Override public String getId()        { return (String) attributes.get("sub"); }
        @Override public String getName()      { return (String) attributes.get("name"); }
        @Override public String getEmail()     { return (String) attributes.get("email"); }
        @Override public String getImageUrl()  { return (String) attributes.get("picture"); }

        @Override
        public String getFirstName() {
            String name = (String) attributes.get("given_name");
            return name != null ? name : getName() != null ? getName().split(" ")[0] : "User";
        }

        @Override
        public String getLastName() {
            String name = (String) attributes.get("family_name");
            return name != null ? name : "";
        }
    }
}
