package ak.dev.irc.app.moderation.enums;

/**
 * What happens to a content unit whose hold window expired without a verdict —
 * MODERATION_ROADMAP.md §5.6. Configured per entity type; the roadmap's §20
 * defaults are fail-closed everywhere except stories.
 */
public enum FallbackPolicy {

    /**
     * Force {@code IN_REVIEW} and surface it at the top of the admin queue with
     * an SLA-breached flag. Content stays hidden until a human looks at it.
     * The right default for a moderation-first product.
     */
    FAIL_CLOSED,

    /**
     * Publish, but flag for priority review and stay ready to retract. Only for
     * low-risk, short-lived entity types (stories) where availability matters
     * more than the residual risk.
     */
    FAIL_OPEN_SHADOW
}
