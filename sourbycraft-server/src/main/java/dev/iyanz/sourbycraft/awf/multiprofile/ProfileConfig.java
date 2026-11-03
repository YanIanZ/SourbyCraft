package dev.iyanz.sourbycraft.awf.multiprofile;

import dev.iyanz.sourbycraft.util.SourbyLogger;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Early reader for {@code aurora.awf.multiprofile.*}; same precedence as the other AWF readers. */
public final class ProfileConfig {

    private ProfileConfig() {}

    public static ProfileSettings readEarly(final Path auroraFile, final Path unifiedFile) {
        final Map<String, Object> values = new HashMap<>();
        for (final Path file : List.of(unifiedFile, auroraFile)) {
            final Path source = dev.iyanz.sourbycraft.config.ConfigSources.existing(file);
            if (source == null) continue;
            try (Reader reader = Files.newBufferedReader(source, StandardCharsets.UTF_8)) {
                final var config = dev.iyanz.sourbycraft.config.ConfigSources.parse(source, reader);
                for (final String key : ProfileSettings.KEYS) {
                    final Object value = config.get(key);
                    if (value != null) values.put(key, value);
                }
            } catch (final Exception unreadable) {
                SourbyLogger.warn("AWF MultiProfile could not read "
                    + dev.iyanz.sourbycraft.config.ConfigParseErrors.describe(source, unreadable)
                    + "; MultiProfile stays disabled");
                return ProfileSettings.DEFAULT;
            }
        }
        final ProfileSettings.Parsed parsed = ProfileSettings.parse(values);
        for (final String key : parsed.invalidKeys()) {
            SourbyLogger.warn("Aurora config key '" + key + "' is invalid; " + fallback(key, parsed.settings()));
        }
        final ProfileSettings.Migration migration = parsed.settings().migration();
        if (migration.mode() != ProfileSettings.MigrationMode.OFF) {
            SourbyLogger.warn("AWF MultiProfile: " + ProfileSettings.MIGRATION_MODE_KEY + " is " + migration.mode().id()
                + (migration.applies()
                    ? "; a pre-Build-51 (format 1) profile store is backed up to '" + migration.backupDir()
                        + "' and then converted to format 2 at startup. This is one way: builds older than Build 51 refuse a format-2 store"
                        + " (rollback = restore the backup)"
                    : "; a pre-Build-51 (format 1) profile store is only inspected and reported, nothing is written")
                + (parsed.settings().enabled() ? "" : " (ignored: aurora.awf.multiprofile.enabled is false)"));
        }
        final ProfileSettings.Coop coop = parsed.settings().coop();
        if (coop.enabled() && !parsed.settings().enabled()) {
            SourbyLogger.warn("AWF MultiProfile: " + ProfileSettings.COOP_ENABLED_KEY
                + " is true but aurora.awf.multiprofile.enabled is false; co-op stays off");
        }
        final ProfileSettings settings = parsed.settings();
        if (settings.enabled() && settings.login().authoritative() == ProfileSettings.Authoritative.FALSE && coop.enabled()) {
            SourbyLogger.warn("AWF MultiProfile: " + ProfileSettings.LOGIN_AUTHORITATIVE_KEY + " is false while "
                + ProfileSettings.COOP_ENABLED_KEY + " is true. A join keeps whatever vanilla playerdata loaded instead of"
                + " restoring the committed state of the active profile, so a quit that was not captured can let a player"
                + " rejoin with items their profile snapshot does not hold, and co-op members cannot switch into a co-op"
                + " profile (COOP_SWITCH_UNAVAILABLE).");
        } else if (settings.enabled() && settings.login().authoritative() == ProfileSettings.Authoritative.AUTO) {
            SourbyLogger.info("AWF MultiProfile: " + ProfileSettings.LOGIN_AUTHORITATIVE_KEY + " is auto: authoritative login is "
                + (coop.enabled() ? "on (co-op is enabled): joins restore the committed profile state, every vanilla player save"
                    + " publishes a profile snapshot, and an unreadable state refuses the join"
                    : "off (co-op is disabled): joins keep vanilla playerdata exactly as in Build 52"));
        }
        if (!settings.adapters().required().isEmpty()) {
            SourbyLogger.info("AWF MultiProfile: adapters forced to REQUIRED by " + ProfileSettings.ADAPTERS_REQUIRED_KEY
                + ": " + String.join(", ", settings.adapters().required()));
        }
        return settings;
    }

    /** What the server does instead of the invalid value, for the WARN. */
    private static String fallback(final String key, final ProfileSettings settings) {
        return switch (key) {
            case ProfileSettings.TYPES_ALLOWED_KEY -> "AWF MultiProfile allows "
                + String.join(", ", ProfileSettings.typeIds(settings.types().allowed()));
            case ProfileSettings.TYPES_DEFAULT_KEY -> "AWF MultiProfile creates '"
                + settings.types().defaultType().id() + "' profiles by default (the default must be in "
                + ProfileSettings.TYPES_ALLOWED_KEY + ")";
            case ProfileSettings.MIGRATION_MODE_KEY -> "AWF MultiProfile uses migration.mode "
                + settings.migration().mode().id() + " (apply needs a valid " + ProfileSettings.MIGRATION_BACKUP_DIR_KEY + ")";
            case ProfileSettings.MIGRATION_BACKUP_DIR_KEY -> "AWF MultiProfile has no migration backup directory";
            case ProfileSettings.COOP_ENABLED_KEY -> "AWF MultiProfile co-op stays "
                + (settings.coop().enabled() ? "on" : "off");
            case ProfileSettings.COOP_MAX_MEMBERS_KEY -> "AWF MultiProfile co-op allows "
                + settings.coop().maxMembers() + " members (allowed " + ProfileSettings.Coop.MIN_MEMBERS + "-"
                + ProfileSettings.Coop.MAX_MEMBERS_LIMIT + ")";
            case ProfileSettings.COOP_INVITE_EXPIRY_KEY -> "AWF MultiProfile co-op invitations expire after "
                + settings.coop().inviteExpiryMinutes() + " minutes";
            case ProfileSettings.COOP_VOTE_EXPIRY_KEY -> "AWF MultiProfile co-op kick votes expire after "
                + settings.coop().kickVoteExpiryMinutes() + " minutes";
            case ProfileSettings.COOP_INACTIVE_DAYS_KEY -> "AWF MultiProfile co-op inactivity rule is "
                + settings.coop().kickVoteInactiveDays() + " days";
            case ProfileSettings.COOP_ALLOWED_TYPES_KEY -> "AWF MultiProfile co-op converts only "
                + String.join(", ", ProfileSettings.typeIds(settings.coop().allowedTypes())) + " profiles";
            case ProfileSettings.SHUTDOWN_CAPTURE_DEADLINE_KEY -> "AWF MultiProfile gives shutdown captures "
                + settings.shutdown().captureDeadlineMillis() + " ms (allowed " + ProfileSettings.Shutdown.MIN_MILLIS
                + "-" + ProfileSettings.Shutdown.MAX_MILLIS + ")";
            case ProfileSettings.LOGIN_AUTHORITATIVE_KEY -> "AWF MultiProfile login.authoritative stays "
                + settings.login().authoritative().id() + " (authoritative login " + (settings.authoritativeLogin() ? "on" : "off") + ")";
            case ProfileSettings.ADAPTERS_REQUIRED_KEY -> "AWF MultiProfile forces only the well-formed adapter ids to REQUIRED ("
                + (settings.adapters().required().isEmpty() ? "none" : String.join(", ", settings.adapters().required()))
                + "); each id is [a-z0-9_.:-]{1,64}, at most " + ProfileSettings.Adapters.MAX_REQUIRED;
            default -> "AWF MultiProfile uses its default";
        };
    }
}
