package ak.dev.irc.app.chat.enums;

/**
 * The live-stream <b>gift catalogue</b> — the symbolic gifts a viewer can send to
 * celebrate a host, the way TikTok / Twitch do. These are <b>symbolic only</b>:
 * there is no wallet, no purchase and no payout. Each gift carries a {@code coins}
 * weight used purely to rank a stream's "top supporters" and to size the on-screen
 * animation; the coins are a score, never money.
 *
 * <p>{@code iconKey} is a stable, frontend-agnostic name (the client maps it to an
 * emoji / Lottie / sprite). Adding a gift is append-only — never renumber or reuse
 * an {@code iconKey}, because past leaderboards reference them.</p>
 */
public enum StreamGift {

    ROSE("Rose", "rose", 1),
    HEART("Heart", "heart", 1),
    CLAP("Applause", "clap", 2),
    STAR("Star", "star", 5),
    CROWN("Crown", "crown", 10),
    ROCKET("Rocket", "rocket", 20),
    DIAMOND("Diamond", "diamond", 50),
    TROPHY("Trophy", "trophy", 100);

    private final String displayName;
    private final String iconKey;
    private final int coins;

    StreamGift(String displayName, String iconKey, int coins) {
        this.displayName = displayName;
        this.iconKey = iconKey;
        this.coins = coins;
    }

    public String displayName() { return displayName; }
    public String iconKey()     { return iconKey; }
    public int coins()          { return coins; }
}
