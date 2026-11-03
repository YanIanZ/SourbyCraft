package dev.iyanz.sourbycraft.command;

import dev.iyanz.sourbycraft.SourbyCraftColors;
import dev.iyanz.sourbycraft.api.profile.CoopDetails;
import dev.iyanz.sourbycraft.api.profile.CoopInviteInfo;
import dev.iyanz.sourbycraft.api.profile.CoopKickVoteInfo;
import dev.iyanz.sourbycraft.api.profile.CoopMemberInfo;
import dev.iyanz.sourbycraft.api.profile.CoopPage;
import dev.iyanz.sourbycraft.api.profile.ProfileDetails;
import dev.iyanz.sourbycraft.api.profile.ProfileErrorCode;
import dev.iyanz.sourbycraft.api.profile.ProfileOperationException;
import dev.iyanz.sourbycraft.awf.multiprofile.ProfileBank;
import dev.iyanz.sourbycraft.awf.multiprofile.ProfileEngine;
import dev.iyanz.sourbycraft.awf.multiprofile.ProfileRuntime;
import dev.iyanz.sourbycraft.awf.multiprofile.ProfileSalvage;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import static net.kyori.adventure.text.Component.text;

/**
 * AWF MultiProfile co-op commands (Build 52): the player-facing {@code /coop} and the operator's
 * {@code /awf multiprofile coop ...} ({@link #admin}).
 *
 * <pre>
 * /coop [info [profile]]               the co-op view of a profile (default: your active profile)
 * /coop convert [profile]              convert your solo profile to co-op (one way)
 * /coop invite &lt;player&gt; [profile]     invite a player (owner or manager)
 * /coop invites                        your open invitations
 * /coop accept [invite]                accept (the only open invitation when none is named)
 * /coop decline &lt;invite&gt;               decline
 * /coop cancel &lt;invite-uuid&gt;           cancel an invitation of your co-op (the full id the invite reply printed)
 * /coop leave &lt;profile&gt;                leave a co-op you do not own (not while it is your active profile)
 * /coop kick &lt;player&gt; [profile]       open a kick vote (owner or manager)
 * /coop vote &lt;vote-uuid&gt; &lt;yes|no&gt;      cast your ballot (the full id /coop info and the kick reply print)
 * /coop members [profile]              the members
 * /coop label &lt;text...|--clear&gt;        the shared display label of your active co-op profile (owner)
 * /coop bank [profile]                 shared and personal bank balances (Build 54)
 * /coop bank transfer &lt;to-personal|to-shared&gt; &lt;amount&gt;   move money between the shared bank and your personal bank
 * /coop bank collections [profile]     the collections and who contributed
 * /coop salvage [profile]              the outstanding salvage of your active co-op
 * /coop salvage claim &lt;salvage-uuid&gt;   deliver a salvage record into your inventory (the profile must be active)
 * </pre>
 *
 * <p>Build 54 (owner decisions OD-3/OD-4): a player deposits or withdraws nothing here; the engine mints no money on a
 * command and the purse is not a transfer party. Ledger credits and debits are the consumer plugin's (API) or the
 * operator's ({@code /awf multiprofile coop bank deposit|withdraw ...} on behalf of an active member, receipted as that
 * member's operation). Salvage is delivered into free inventory and ender-chest slots; what does not fit stays in the record.</p>
 *
 * <p>A {@code profile} is a profile UUID or the name of one of your profiles; an {@code invite} is its UUID or
 * the first characters of it ({@code accept}, {@code decline}); {@code cancel} and {@code vote} take the full UUID
 * the replies print. Every action is asynchronous: the engine runs it on the profile I/O lane and the reply comes
 * back on the sender's own scheduler ({@link SourbyReply#run}, the player's entity scheduler), so nothing blocks a
 * region thread; replies name players through the online list and the server's in-memory name cache only, never
 * through a player-data read. {@code info} and {@code members} show a co-op to its members only. Permission
 * {@code sourbycraft.coop.use}, granted to everyone by default ({@link SourbyCraftCommands} registers it); the
 * operator form needs {@code sourbycraft.command.awf.multiprofile} and, for every change,
 * {@code sourbycraft.command.awf.admin}. The command is registered only while
 * {@code aurora.awf.multiprofile.coop.enabled} is true (RESTART_REQUIRED); it never replaces a plugin's
 * {@code /coop} (see {@link SourbyCraftCommands}).</p>
 */
public final class CoopCommand extends Command {

    /** The player permission; default true (registered by {@link SourbyCraftCommands}). */
    public static final String PERMISSION = "sourbycraft.coop.use";
    static final String ADMIN_PERMISSION = "sourbycraft.command.awf.admin";

    private static final List<String> SUBCOMMANDS = List.of("info", "convert", "invite", "invites", "accept", "decline", "cancel",
        "leave", "kick", "vote", "members", "label", "bank", "salvage");

    private final BiConsumer<CommandSender, Runnable> reply;

    public CoopCommand(final String name) {
        this(name, SourbyReply::run);
    }

    CoopCommand(final String name, final BiConsumer<CommandSender, Runnable> reply) {
        super(name);
        this.description = "Co-op profiles: invite, accept, leave, vote-kick (AWF MultiProfile)";
        this.usageMessage = "/coop <info|convert|invite|invites|accept|decline|cancel|leave|kick|vote|members|label|switch|bank|salvage>";
        this.setPermission(PERMISSION);
        this.reply = reply;
    }

    @Override
    public boolean execute(final CommandSender s, final String alias, final String[] args) {
        if (!testPermission(s)) return true;
        if (!(s instanceof Player player)) {
            s.sendMessage(text("/coop is for players; operators use /awf multiprofile coop", SourbyCraftColors.WARNING));
            return true;
        }
        if (dev.iyanz.sourbycraft.core.AuroraRuntime.stopping()) {
            s.sendMessage(text("The server is stopping; /coop starts no new work", SourbyCraftColors.DANGER));
            return true;
        }
        final ProfileEngine engine = ProfileRuntime.engineOrNull();
        if (engine == null) {
            s.sendMessage(text("Profiles are not available on this server", SourbyCraftColors.DANGER));
            return true;
        }
        final UUID me = player.getUniqueId();
        final String sub = args.length == 0 ? "info" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "info" -> withProfile(s, engine, me, arg(args, 1), profile -> info(s, engine, me, profile));
            case "convert" -> withProfile(s, engine, me, arg(args, 1), profile ->
                done(s, engine.convertToCoop(me, profile), details -> s.sendMessage(text("Profile " + profile
                    + " is now a co-op profile (up to " + details.maxMembers() + " members). Invite with /coop invite <player>",
                    SourbyCraftColors.SUCCESS))));
            case "invite" -> {
                final UUID invitee = playerArg(s, arg(args, 1), "/coop invite <player> [profile]");
                if (invitee == null) return true;
                withProfile(s, engine, me, arg(args, 2), profile -> done(s, engine.invite(me, profile, invitee), invite ->
                    s.sendMessage(text("Invited " + nameOf(invitee) + " (invite " + invite.id() + ", expires in "
                        + minutesLeft(invite.expiresAtMillis()) + "). They accept with /coop accept; /coop cancel " + invite.id()
                        + " withdraws it", SourbyCraftColors.SUCCESS))));
            }
            case "invites" -> done(s, engine.invitesFor(me, null), page -> {
                s.sendMessage(UiPanel.header("Your co-op invitations"));
                if (page.items().isEmpty()) s.sendMessage(UiPanel.row("none", "nobody invited you"));
                for (final CoopInviteInfo invite : page.items()) {
                    s.sendMessage(UiPanel.row(shortId(invite.id()), "from " + nameOf(invite.inviter()) + " to profile "
                        + invite.profileId() + ", expires in " + minutesLeft(invite.expiresAtMillis())));
                }
                if (page.nextCursor() != null) s.sendMessage(UiPanel.hint("more invitations exist; decline some to see the rest"));
                s.sendMessage(UiPanel.footer());
            });
            case "accept" -> withInvite(s, engine, me, arg(args, 1), invite -> done(s, engine.acceptInvite(me, invite), member ->
                s.sendMessage(text("You joined the co-op as " + member.role() + ". Play it with /coop switch <profile>",
                    SourbyCraftColors.SUCCESS))));
            case "decline" -> withInvite(s, engine, me, arg(args, 1), invite -> done(s, engine.declineInvite(me, invite), ignored ->
                s.sendMessage(text("Declined invite " + shortId(invite), SourbyCraftColors.SUCCESS))));
            case "cancel" -> {
                final UUID invite = uuid(s, arg(args, 1), "/coop cancel <invite-uuid>");
                if (invite == null) return true;
                done(s, engine.cancelInvite(me, invite), ignored -> s.sendMessage(text("Cancelled invite " + shortId(invite),
                    SourbyCraftColors.SUCCESS)));
            }
            case "leave" -> {
                if (arg(args, 1) == null) {
                    s.sendMessage(text("Usage: /coop leave <profile> (name or UUID; if it is your active profile you are"
                        + " switched to another profile first)", SourbyCraftColors.WARNING));
                    return true;
                }
                withProfile(s, engine, me, arg(args, 1), profile -> done(s, engine.leave(me, profile), ignored ->
                    s.sendMessage(text("You left co-op profile " + profile + ". Your items there are recorded as salvage; a member"
                        + " with the profile active claims them with /coop salvage claim", SourbyCraftColors.SUCCESS))));
            }
            case "switch" -> {
                if (arg(args, 1) == null) {
                    s.sendMessage(text("Usage: /coop switch <profile> (name or UUID of a co-op you are a member of)",
                        SourbyCraftColors.WARNING));
                    return true;
                }
                withProfile(s, engine, me, arg(args, 1), profile -> done(s, engine.switchProfile(me, profile), result -> {
                    if (result.success()) {
                        s.sendMessage(text("Switched into " + profile + " (" + result.durationMillis() + " ms)", SourbyCraftColors.SUCCESS));
                    } else {
                        s.sendMessage(text("Refused (" + result.errorCode() + "): " + result.message() + hint(result.errorCode()),
                            SourbyCraftColors.DANGER));
                    }
                }));
            }
            case "kick" -> {
                final UUID target = playerArg(s, arg(args, 1), "/coop kick <player> [profile]");
                if (target == null) return true;
                withProfile(s, engine, me, arg(args, 2), profile -> done(s, engine.startKickVote(me, profile, target),
                    vote -> voteReply(s, vote)));
            }
            case "vote" -> {
                final String choice = arg(args, 2);
                if (choice == null || !(choice.equalsIgnoreCase("yes") || choice.equalsIgnoreCase("no"))) {
                    s.sendMessage(text("Usage: /coop vote <vote-uuid> <yes|no>", SourbyCraftColors.WARNING));
                    return true;
                }
                final UUID vote = uuid(s, arg(args, 1), "/coop vote <vote-uuid> <yes|no>");
                if (vote == null) return true;
                done(s, engine.voteKick(me, vote, choice.equalsIgnoreCase("yes")), info -> voteReply(s, info));
            }
            case "members" -> withProfile(s, engine, me, arg(args, 1), profile -> members(s, engine, me, profile));
            case "label" -> {
                if (args.length < 2) {
                    s.sendMessage(text("Usage: /coop label <text...|--clear> (your active co-op profile; owner only)",
                        SourbyCraftColors.WARNING));
                    return true;
                }
                final String label = args[1].equals("--clear") ? null : String.join(" ", Arrays.copyOfRange(args, 1, args.length));
                withProfile(s, engine, me, null, profile -> done(s, engine.setCoopLabel(me, profile, label), ignored ->
                    s.sendMessage(text(label == null ? "Label cleared" : "Label set to \"" + label + "\"", SourbyCraftColors.SUCCESS))));
            }
            case "bank" -> bank(s, engine, me, args);
            case "salvage" -> salvage(s, engine, me, args);
            default -> usage(s);
        }
        return true;
    }

    // ------------------------------------------------------------------ operator form

    /**
     * {@code /awf multiprofile coop <sub> ...}: the same actions on behalf of a named player, for operators.
     * {@code args[0]} is {@code multiprofile}, {@code args[1]} {@code coop}. Reads ({@code info}, {@code members},
     * {@code invites}) need the multiprofile permission the caller already checked; every change also needs
     * {@value #ADMIN_PERMISSION}.
     */
    static void admin(final CommandSender s, final ProfileEngine engine, final String[] args, final BiConsumer<CommandSender, Runnable> reply,
                      final boolean admin) {
        final String sub = args.length > 2 ? args[2].toLowerCase(Locale.ROOT) : "";
        final boolean read = sub.equals("info") || sub.equals("members") || sub.equals("invites")
            || (sub.equals("bank") && (arg(args, 3) == null || arg(args, 3).equalsIgnoreCase("balance") || arg(args, 3).equalsIgnoreCase("collections")))
            || (sub.equals("salvage") && (arg(args, 3) == null || arg(args, 3).equalsIgnoreCase("list")));
        if (!read && !sub.isEmpty() && SUBCOMMANDS.contains(sub) && !admin) {
            s.sendMessage(text("You do not have " + ADMIN_PERMISSION + "; co-op changes on behalf of a player need it",
                SourbyCraftColors.DANGER));
            return;
        }
        final Responder r = new Responder(s, reply);
        switch (sub) {
            case "info" -> {
                final UUID profile = uuid(s, arg(args, 3), "/awf multiprofile coop info <profile-uuid>");
                if (profile != null) r.info(engine, null, profile);
            }
            case "members" -> {
                final UUID profile = uuid(s, arg(args, 3), "/awf multiprofile coop members <profile-uuid> [cursor]");
                if (profile != null) r.members(engine, profile, arg(args, 4));
            }
            case "invites" -> {
                if ("for".equalsIgnoreCase(arg(args, 3))) {
                    final UUID player = playerArg(s, arg(args, 4), "/awf multiprofile coop invites for <player> [cursor]");
                    if (player != null) r.done(engine.invitesFor(player, arg(args, 5)), page -> r.invites("Invitations of " + player, page));
                    return;
                }
                final UUID profile = uuid(s, arg(args, 3), "/awf multiprofile coop invites <profile-uuid> [cursor] | invites for <player>");
                if (profile != null) r.done(engine.invitesOf(null, profile, arg(args, 4)), page -> r.invites("Invitations of " + profile, page));
            }
            case "convert" -> {
                final UUID owner = playerArg(s, arg(args, 3), "/awf multiprofile coop convert <owner> <profile-uuid>");
                final UUID profile = owner == null ? null : uuid(s, arg(args, 4), "/awf multiprofile coop convert <owner> <profile-uuid>");
                if (profile != null) r.done(engine.convertToCoop(owner, profile), details -> r.ok("Converted " + profile + " to co-op"));
            }
            case "invite" -> {
                final String usage = "/awf multiprofile coop invite <actor> <profile-uuid> <invitee>";
                final UUID actor = playerArg(s, arg(args, 3), usage);
                final UUID profile = actor == null ? null : uuid(s, arg(args, 4), usage);
                final UUID invitee = profile == null ? null : playerArg(s, arg(args, 5), usage);
                if (invitee != null) r.done(engine.invite(actor, profile, invitee), invite -> r.ok("Invite " + invite.id() + " to "
                    + invitee + " expires at " + java.time.Instant.ofEpochMilli(invite.expiresAtMillis())));
            }
            case "accept", "decline" -> {
                final String usage = "/awf multiprofile coop " + sub + " <player> <invite-uuid>";
                final UUID player = playerArg(s, arg(args, 3), usage);
                final UUID invite = player == null ? null : uuid(s, arg(args, 4), usage);
                if (invite == null) return;
                if (sub.equals("accept")) {
                    r.done(engine.acceptInvite(player, invite), member -> r.ok(player + " joined as " + member.role()));
                } else {
                    r.done(engine.declineInvite(player, invite), ignored -> r.ok("Declined invite " + invite));
                }
            }
            case "cancel" -> {
                final String usage = "/awf multiprofile coop cancel <actor> <invite-uuid>";
                final UUID actor = playerArg(s, arg(args, 3), usage);
                final UUID invite = actor == null ? null : uuid(s, arg(args, 4), usage);
                if (invite != null) r.done(engine.cancelInvite(actor, invite), ignored -> r.ok("Cancelled invite " + invite));
            }
            case "leave" -> {
                final String usage = "/awf multiprofile coop leave <player> <profile-uuid>";
                final UUID player = playerArg(s, arg(args, 3), usage);
                final UUID profile = player == null ? null : uuid(s, arg(args, 4), usage);
                if (profile != null) r.done(engine.leave(player, profile), ignored -> r.ok(player + " left " + profile
                    + "; a salvage record was written (see /awf multiprofile coop salvage list " + profile + ")"));
            }
            case "kick" -> {
                final String usage = "/awf multiprofile coop kick <actor> <profile-uuid> <target>";
                final UUID actor = playerArg(s, arg(args, 3), usage);
                final UUID profile = actor == null ? null : uuid(s, arg(args, 4), usage);
                final UUID target = profile == null ? null : playerArg(s, arg(args, 5), usage);
                if (target != null) r.done(engine.startKickVote(actor, profile, target), vote -> r.vote(vote));
            }
            case "vote" -> {
                final String usage = "/awf multiprofile coop vote <voter> <vote-uuid> <yes|no>";
                final UUID voter = playerArg(s, arg(args, 3), usage);
                final UUID vote = voter == null ? null : uuid(s, arg(args, 4), usage);
                final String choice = arg(args, 5);
                if (vote == null) return;
                if (choice == null || !(choice.equalsIgnoreCase("yes") || choice.equalsIgnoreCase("no"))) {
                    s.sendMessage(text("Usage: " + usage, SourbyCraftColors.WARNING));
                    return;
                }
                r.done(engine.voteKick(voter, vote, choice.equalsIgnoreCase("yes")), info -> r.vote(info));
            }
            case "bank" -> adminBank(s, engine, args, r);
            case "salvage" -> adminSalvage(s, engine, args, r);
            case "label" -> {
                final String usage = "/awf multiprofile coop label <actor> <profile-uuid> <text...|--clear>";
                final UUID actor = playerArg(s, arg(args, 3), usage);
                final UUID profile = actor == null ? null : uuid(s, arg(args, 4), usage);
                if (profile == null) return;
                if (args.length < 6) {
                    s.sendMessage(text("Usage: " + usage, SourbyCraftColors.WARNING));
                    return;
                }
                final String label = args[5].equals("--clear") ? null : String.join(" ", Arrays.copyOfRange(args, 5, args.length));
                r.done(engine.setCoopLabel(actor, profile, label), ignored -> r.ok(label == null ? "Label cleared" : "Label set"));
            }
            default -> s.sendMessage(text("Usage: /awf multiprofile coop <convert <owner> <profile>|invite <actor> <profile> <invitee>|"
                + "accept <player> <invite>|decline <player> <invite>|cancel <actor> <invite>|leave <player> <profile>|"
                + "kick <actor> <profile> <target>|vote <voter> <vote> <yes|no>|members <profile> [cursor]|"
                + "invites <profile> [cursor]|invites for <player>|info <profile>|label <actor> <profile> <text|--clear>|"
                + "bank ...|salvage ...>",
                SourbyCraftColors.WARNING));
        }
    }

    /** Replies of the operator form: each on the sender's own scheduler. */
    private record Responder(CommandSender s, BiConsumer<CommandSender, Runnable> reply) {

        <T> void done(final CompletableFuture<T> future, final Consumer<T> success) {
            future.whenComplete((value, failed) -> this.reply.accept(this.s, () -> {
                if (failed != null) {
                    fail(this.s, failed);
                } else {
                    success.accept(value);
                }
            }));
        }

        void ok(final String message) {
            this.s.sendMessage(text(message, SourbyCraftColors.SUCCESS));
        }

        /** {@code viewer} is the player asking (members only), or {@code null} for the operator's unchecked view. */
        void info(final ProfileEngine engine, final UUID viewer, final UUID profile) {
            done(engine.coopDetails(viewer, profile), details -> {
                if (details.isEmpty()) {
                    this.s.sendMessage(text("Profile " + profile + " is not a co-op profile (or does not exist)", SourbyCraftColors.WARNING));
                    return;
                }
                details(this.s, details.get());
                done(engine.openKickVote(viewer, profile), vote -> vote.ifPresent(open -> this.s.sendMessage(UiPanel.row("open kick vote",
                    open.id() + " against " + nameOf(open.target()) + " (" + open.votes() + "/" + open.electorateSize() + ", " + open.state()
                    + "); /coop vote " + open.id() + " <yes|no>"))));
            });
        }

        void members(final ProfileEngine engine, final UUID profile, final String cursor) {
            done(engine.members(profile, cursor), page -> {
                this.s.sendMessage(UiPanel.header("Members of " + profile));
                for (final CoopMemberInfo member : page.items()) {
                    this.s.sendMessage(UiPanel.row(member.playerId().toString(), member.role() + ", joined "
                        + java.time.Instant.ofEpochMilli(member.joinedAtMillis())));
                }
                if (page.nextCursor() != null) this.s.sendMessage(UiPanel.hint("next page: " + page.nextCursor()));
                this.s.sendMessage(UiPanel.footer());
            });
        }

        void invites(final String title, final CoopPage<CoopInviteInfo> page) {
            this.s.sendMessage(UiPanel.header(title));
            if (page.items().isEmpty()) this.s.sendMessage(UiPanel.row("none", "no open invitations"));
            for (final CoopInviteInfo invite : page.items()) {
                this.s.sendMessage(UiPanel.row(invite.id().toString(), "profile " + invite.profileId() + ", " + invite.inviter() + " -> "
                    + invite.invitee() + ", expires " + java.time.Instant.ofEpochMilli(invite.expiresAtMillis())));
            }
            if (page.nextCursor() != null) this.s.sendMessage(UiPanel.hint("next page: " + page.nextCursor()));
            this.s.sendMessage(UiPanel.footer());
        }

        void vote(final CoopKickVoteInfo vote) {
            voteReply(this.s, vote);
        }
    }

    // ------------------------------------------------------------------ player helpers

    private <T> void done(final CommandSender s, final CompletableFuture<T> future, final Consumer<T> success) {
        new Responder(s, this.reply).done(future, success);
    }

    private void info(final CommandSender s, final ProfileEngine engine, final UUID viewer, final UUID profile) {
        new Responder(s, this.reply).info(engine, viewer, profile);
    }

    private void members(final CommandSender s, final ProfileEngine engine, final UUID viewer, final UUID profile) {
        done(s, engine.members(viewer, profile, null), page -> {
            s.sendMessage(UiPanel.header("Co-op members"));
            for (final CoopMemberInfo member : page.items()) s.sendMessage(UiPanel.row(nameOf(member.playerId()), member.role().name()));
            if (page.nextCursor() != null) s.sendMessage(UiPanel.hint("more members than one page"));
            s.sendMessage(UiPanel.footer());
        });
    }

    /**
     * Resolves {@code typed} (a profile UUID or the name of one of the player's profiles; {@code null} = the active
     * profile) on the I/O lane, then runs {@code action} on the player's scheduler.
     */
    private void withProfile(final CommandSender s, final ProfileEngine engine, final UUID me, final String typed,
                             final Consumer<UUID> action) {
        if (typed != null) {
            try {
                action.accept(UUID.fromString(typed));
                return;
            } catch (final IllegalArgumentException notUuid) {
                // a name; resolved below
            }
        }
        final CompletableFuture<Optional<UUID>> resolved = typed == null
            ? engine.active(me).thenApply(active -> active.map(info -> info.id()))
            : engine.listDetails(me).thenApply(profiles -> profiles.stream()
                .filter(details -> details.name().equalsIgnoreCase(typed)).map(ProfileDetails::id).findFirst());
        done(s, resolved, found -> {
            if (found.isEmpty()) {
                s.sendMessage(text(typed == null ? "You have no active profile; name one (its name or UUID)"
                    : "You have no profile named " + typed, SourbyCraftColors.DANGER));
                return;
            }
            action.accept(found.get());
        });
    }

    /** Resolves an invitation: its UUID, a prefix of it, or (none typed) the only open invitation. */
    private void withInvite(final CommandSender s, final ProfileEngine engine, final UUID me, final String typed,
                            final Consumer<UUID> action) {
        if (typed != null) {
            try {
                action.accept(UUID.fromString(typed));
                return;
            } catch (final IllegalArgumentException notUuid) {
                // a prefix; resolved below
            }
        }
        done(s, engine.invitesFor(me, null), page -> {
            final List<CoopInviteInfo> matches = page.items().stream()
                .filter(invite -> typed == null || invite.id().toString().startsWith(typed.toLowerCase(Locale.ROOT))).toList();
            if (matches.size() == 1) {
                action.accept(matches.getFirst().id());
                return;
            }
            s.sendMessage(text(matches.isEmpty() ? "No open invitation" + (typed == null ? "" : " starts with " + typed)
                : matches.size() + " open invitations match; name one (see /coop invites)", SourbyCraftColors.WARNING));
        });
    }

    private static void details(final CommandSender s, final CoopDetails details) {
        s.sendMessage(UiPanel.header("Co-op " + (details.label().isEmpty() ? details.profileId().toString() : details.label())));
        s.sendMessage(UiPanel.row("profile", details.profileId().toString()));
        s.sendMessage(UiPanel.row("members", details.members().size() + " of " + details.maxMembers()));
        for (final CoopMemberInfo member : details.members()) s.sendMessage(UiPanel.row("  " + nameOf(member.playerId()), member.role().name()));
        s.sendMessage(UiPanel.footer());
    }

    private static void voteReply(final CommandSender s, final CoopKickVoteInfo vote) {
        final String state = switch (vote.state()) {
            case EXECUTED -> "passed; " + nameOf(vote.target()) + " was removed";
            case PASSED -> "passed; the removal is pending (approve it again to retry)";
            case OPEN -> vote.votes() + " of " + vote.electorateSize() + " approvals; expires in " + minutesLeft(vote.expiresAtMillis());
            default -> vote.state().name().toLowerCase(Locale.ROOT);
        };
        s.sendMessage(text("Kick vote " + vote.id() + " against " + nameOf(vote.target()) + ": " + state
            + (vote.state() == dev.iyanz.sourbycraft.api.profile.CoopKickVoteState.OPEN ? ". Members vote with /coop vote " + vote.id()
            + " <yes|no>" : ""), SourbyCraftColors.SUCCESS));
    }

    private static void usage(final CommandSender s) {
        s.sendMessage(UiPanel.header("Co-op"));
        s.sendMessage(UiPanel.row("/coop info [profile]", "your co-op"));
        s.sendMessage(UiPanel.row("/coop convert [profile]", "make a solo profile co-op (one way)"));
        s.sendMessage(UiPanel.row("/coop invite <player> [profile]", "invite"));
        s.sendMessage(UiPanel.row("/coop invites | accept [invite] | decline <invite>", "your invitations"));
        s.sendMessage(UiPanel.row("/coop cancel <invite>", "cancel an invitation"));
        s.sendMessage(UiPanel.row("/coop leave <profile>", "leave a co-op (not your active profile)"));
        s.sendMessage(UiPanel.row("/coop kick <player> [profile] | vote <vote> <yes|no>", "kick votes"));
        s.sendMessage(UiPanel.row("/coop members [profile] | label <text|--clear>", "members, shared label"));
        s.sendMessage(UiPanel.row("/coop bank [transfer <to-personal|to-shared> <amount>|collections]", "shared and personal bank"));
        s.sendMessage(UiPanel.row("/coop salvage [claim <salvage-uuid>]", "items left by removed members"));
        s.sendMessage(UiPanel.footer());
    }

    // ------------------------------------------------------------------ bank and salvage (Build 54)

    /** {@code /coop bank [profile] | bank transfer <to-personal|to-shared> <amount> [profile] | bank collections [profile]}. */
    private void bank(final CommandSender s, final ProfileEngine engine, final UUID me, final String[] args) {
        final String sub = arg(args, 1) == null ? "balance" : args[1].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "transfer" -> {
                final String direction = arg(args, 2);
                final String amount = arg(args, 3);
                if (direction == null || amount == null) {
                    s.sendMessage(text("Usage: /coop bank transfer <to-personal|to-shared> <amount> [profile]", SourbyCraftColors.WARNING));
                    return;
                }
                final ProfileBank.Account from;
                final ProfileBank.Account to;
                switch (direction.toLowerCase(Locale.ROOT)) {
                    case "to-personal", "withdraw" -> {
                        from = ProfileBank.Account.SHARED;
                        to = ProfileBank.Account.PERSONAL;
                    }
                    case "to-shared", "deposit" -> {
                        from = ProfileBank.Account.PERSONAL;
                        to = ProfileBank.Account.SHARED;
                    }
                    default -> {
                        s.sendMessage(text("Direction is to-personal (shared -> your personal bank) or to-shared; the purse is not a"
                            + " transfer party (owner decision OD-4)", SourbyCraftColors.WARNING));
                        return;
                    }
                }
                withProfile(s, engine, me, arg(args, 4), profile -> withAmount(s, engine, amount, minor ->
                    done(s, engine.transfer(me, profile, from, to, minor), view -> bankView(s, view, me))));
            }
            case "collections" -> withProfile(s, engine, me, arg(args, 2), profile -> done(s, engine.collections(me, profile),
                collections -> collectionsView(s, collections)));
            case "balance" -> withProfile(s, engine, me, arg(args, 2), profile -> done(s, engine.bank(me, profile), view -> bankView(s, view, me)));
            default -> {
                // "/coop bank <profile>": the balances of that profile
                withProfile(s, engine, me, args[1], profile -> done(s, engine.bank(me, profile), view -> bankView(s, view, me)));
            }
        }
    }

    /** {@code /coop salvage [profile] | salvage claim <salvage-uuid>}. */
    private void salvage(final CommandSender s, final ProfileEngine engine, final UUID me, final String[] args) {
        if ("claim".equalsIgnoreCase(arg(args, 1))) {
            final UUID salvage = uuid(s, arg(args, 2), "/coop salvage claim <salvage-uuid> (the full id /coop salvage prints)");
            if (salvage == null) return;
            done(s, engine.claimSalvage(me, salvage), delivery -> deliveryReply(s, delivery));
            return;
        }
        final String typed = "list".equalsIgnoreCase(arg(args, 1)) ? arg(args, 2) : arg(args, 1);
        withProfile(s, engine, me, typed, profile -> done(s, engine.salvage(me, profile, null), listing -> salvageView(s, listing)));
    }

    /** The operator's bank form; every change runs on behalf of a named active member and is receipted as that member's operation. */
    private static void adminBank(final CommandSender s, final ProfileEngine engine, final String[] args, final Responder r) {
        final String sub = arg(args, 3) == null ? "balance" : args[3].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "balance" -> {
                final UUID profile = uuid(s, arg(args, 4), "/awf multiprofile coop bank balance <profile-uuid>");
                if (profile != null) r.done(engine.bank(null, profile), view -> bankView(s, view, null));
            }
            case "collections" -> {
                final UUID profile = uuid(s, arg(args, 4), "/awf multiprofile coop bank collections <profile-uuid>");
                if (profile != null) r.done(engine.collections(null, profile), collections -> collectionsView(s, collections));
            }
            case "deposit", "withdraw", "personal-deposit", "personal-withdraw" -> {
                final String usage = "/awf multiprofile coop bank " + sub + " <actor> <profile-uuid> <amount>";
                final UUID actor = playerArg(s, arg(args, 4), usage);
                final UUID profile = actor == null ? null : uuid(s, arg(args, 5), usage);
                if (profile == null || arg(args, 6) == null) {
                    if (profile != null) s.sendMessage(text("Usage: " + usage, SourbyCraftColors.WARNING));
                    return;
                }
                withAmount(s, engine, arg(args, 6), minor -> r.done(switch (sub) {
                    case "deposit" -> engine.deposit(actor, profile, minor);
                    case "withdraw" -> engine.withdraw(actor, profile, minor);
                    case "personal-deposit" -> engine.depositPersonal(actor, profile, minor);
                    default -> engine.withdrawPersonal(actor, profile, minor);
                }, view -> bankView(s, view, actor)));
            }
            case "transfer" -> {
                final String usage = "/awf multiprofile coop bank transfer <actor> <profile-uuid> <shared|personal> <shared|personal> <amount>";
                final UUID actor = playerArg(s, arg(args, 4), usage);
                final UUID profile = actor == null ? null : uuid(s, arg(args, 5), usage);
                if (profile == null || arg(args, 8) == null) {
                    if (profile != null) s.sendMessage(text("Usage: " + usage, SourbyCraftColors.WARNING));
                    return;
                }
                final ProfileBank.Account from;
                final ProfileBank.Account to;
                try {
                    from = ProfileBank.Account.parse(arg(args, 6));
                    to = ProfileBank.Account.parse(arg(args, 7));
                } catch (final IllegalArgumentException invalid) {
                    s.sendMessage(text(invalid.getMessage() + ". Usage: " + usage, SourbyCraftColors.DANGER));
                    return;
                }
                withAmount(s, engine, arg(args, 8), minor -> r.done(engine.transfer(actor, profile, from, to, minor), view -> bankView(s, view, actor)));
            }
            case "contribute" -> {
                final String usage = "/awf multiprofile coop bank contribute <actor> <profile-uuid> <collection> <amount>";
                final UUID actor = playerArg(s, arg(args, 4), usage);
                final UUID profile = actor == null ? null : uuid(s, arg(args, 5), usage);
                if (profile == null || arg(args, 7) == null) {
                    if (profile != null) s.sendMessage(text("Usage: " + usage, SourbyCraftColors.WARNING));
                    return;
                }
                withAmount(s, engine, arg(args, 7), minor -> r.done(engine.contribute(actor, profile, arg(args, 6), minor), view -> bankView(s, view, actor)));
            }
            case "interest" -> {
                final String usage = "/awf multiprofile coop bank interest <owner> <profile-uuid>";
                final UUID actor = playerArg(s, arg(args, 4), usage);
                final UUID profile = actor == null ? null : uuid(s, arg(args, 5), usage);
                if (profile != null) r.done(engine.applyInterest(actor, profile), view -> bankView(s, view, actor));
            }
            default -> s.sendMessage(text("Usage: /awf multiprofile coop bank <balance <profile>|collections <profile>|deposit|withdraw|"
                + "personal-deposit|personal-withdraw <actor> <profile> <amount>|transfer <actor> <profile> <from> <to> <amount>|"
                + "contribute <actor> <profile> <collection> <amount>|interest <owner> <profile>>", SourbyCraftColors.WARNING));
        }
    }

    private static void adminSalvage(final CommandSender s, final ProfileEngine engine, final String[] args, final Responder r) {
        final String sub = arg(args, 3) == null ? "list" : args[3].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "list" -> {
                final UUID profile = uuid(s, arg(args, 4), "/awf multiprofile coop salvage list <profile-uuid> [cursor]");
                if (profile != null) r.done(engine.salvage(null, profile, arg(args, 5)), listing -> salvageView(s, listing));
            }
            case "claim" -> {
                final String usage = "/awf multiprofile coop salvage claim <claimant> <salvage-uuid>";
                final UUID claimant = playerArg(s, arg(args, 4), usage);
                final UUID salvage = claimant == null ? null : uuid(s, arg(args, 5), usage);
                if (salvage != null) r.done(engine.claimSalvage(claimant, salvage), delivery -> deliveryReply(s, delivery));
            }
            default -> s.sendMessage(text("Usage: /awf multiprofile coop salvage <list <profile> [cursor]|claim <claimant> <salvage-uuid>>",
                SourbyCraftColors.WARNING));
        }
    }

    /** Parses an amount in the engine's currency (exact; decimals only up to the currency scale) and runs {@code action} with the minor units. */
    private static void withAmount(final CommandSender s, final ProfileEngine engine, final String typed, final java.util.function.LongConsumer action) {
        final long minor;
        try {
            minor = ProfileBank.parseAmount(typed, engine.bankPolicy().currency());
        } catch (final ProfileOperationException invalid) {
            s.sendMessage(text(invalid.getMessage(), SourbyCraftColors.DANGER));
            return;
        }
        action.accept(minor);
    }

    private static void bankView(final CommandSender s, final ProfileBank.View view, final UUID viewer) {
        final var currency = view.currency();
        s.sendMessage(UiPanel.header("Bank of " + view.profileId()));
        s.sendMessage(UiPanel.row("shared", ProfileBank.format(view.sharedBalance(), currency) + " " + currency.currencyId()
            + (view.sharedCapacity() > 0 ? " of " + ProfileBank.format(view.sharedCapacity(), currency) : "")
            + (view.pendingTransfers() > 0 ? " (" + ProfileBank.format(view.sharedAvailable(), currency) + " available)" : "")));
        if (viewer != null) {
            s.sendMessage(UiPanel.row("personal", ProfileBank.format(view.personalBalance(), currency) + " " + currency.currencyId()
                + (view.personalCapacity() > 0 ? " of " + ProfileBank.format(view.personalCapacity(), currency) : "")));
        } else {
            for (final var entry : view.personalBalances().entrySet()) {
                s.sendMessage(UiPanel.row("  " + nameOf(entry.getKey()), ProfileBank.format(entry.getValue(), currency)));
            }
        }
        s.sendMessage(UiPanel.hint("ledger revision " + view.revision() + "; /coop bank transfer <to-personal|to-shared> <amount>"));
        s.sendMessage(UiPanel.footer());
    }

    private static void collectionsView(final CommandSender s, final ProfileBank.Collections collections) {
        s.sendMessage(UiPanel.header("Collections of " + collections.profileId()));
        if (collections.totals().isEmpty()) s.sendMessage(UiPanel.row("none", "nothing contributed yet"));
        for (final var entry : collections.totals().entrySet()) {
            final StringBuilder who = new StringBuilder();
            final var ledger = collections.contributions().getOrDefault(entry.getKey(), java.util.Map.of());
            for (final var contribution : ledger.entrySet()) {
                if (!who.isEmpty()) who.append(", ");
                who.append(nameOf(contribution.getKey())).append(' ').append(contribution.getValue());
            }
            s.sendMessage(UiPanel.row(entry.getKey(), entry.getValue() + (who.isEmpty() ? "" : " (" + who + ")")));
        }
        s.sendMessage(UiPanel.footer());
    }

    private static void salvageView(final CommandSender s, final ProfileEngine.SalvageListing listing) {
        s.sendMessage(UiPanel.header("Salvage"));
        if (listing.items().isEmpty()) s.sendMessage(UiPanel.row("none", "no outstanding salvage"));
        for (final ProfileSalvage salvage : listing.items()) {
            final String what = switch (salvage.state()) {
                case UNAVAILABLE -> ProfileSalvage.NIL.equals(salvage.sourceSnapshotId()) ? "nothing was published; claim closes it"
                    : "items of the removed member's last snapshot";
                case AVAILABLE -> "remainder of an earlier claim (" + salvage.payloadLength() + " bytes)";
                case DELIVERED -> "delivered";
            };
            s.sendMessage(UiPanel.row(salvage.id().toString(), "from " + nameOf(salvage.removedMember()) + ": " + what));
        }
        if (listing.nextCursor() != null) s.sendMessage(UiPanel.hint("next page: " + listing.nextCursor()));
        s.sendMessage(UiPanel.hint("/coop salvage claim <salvage-uuid> delivers into your free inventory and ender chest slots"));
        s.sendMessage(UiPanel.footer());
    }

    private static void deliveryReply(final CommandSender s, final ProfileEngine.SalvageDelivery delivery) {
        final String rest = delivery.complete() ? "the record is delivered" : delivery.remaining() + " stack(s) stay in the record (free some slots and claim again)";
        s.sendMessage(text("Salvage " + shortId(delivery.salvageId()) + ": " + delivery.delivered() + " stack(s) delivered; " + rest
            + (delivery.applied() ? "" : ". The delivery is committed but could not be applied to the player; it is restored at the next login")
            + " (operation " + delivery.operationId() + ")", delivery.applied() ? SourbyCraftColors.SUCCESS : SourbyCraftColors.WARNING));
    }

    // ------------------------------------------------------------------ shared helpers

    /** A co-op failure with its error code and what the player or operator can do. */
    static void fail(final CommandSender s, final Throwable failed) {
        Throwable cause = failed;
        while (cause instanceof CompletionException && cause.getCause() != null) cause = cause.getCause();
        if (!(cause instanceof ProfileOperationException operation)) {
            s.sendMessage(text("Failed: " + cause.getMessage(), SourbyCraftColors.DANGER));
            return;
        }
        s.sendMessage(text("Failed (" + operation.code() + "): " + operation.getMessage() + hint(operation.code()), SourbyCraftColors.DANGER));
    }

    /** The operator- and player-facing hint of a co-op error code, or an empty string. */
    static String hint(final ProfileErrorCode code) {
        return switch (code) {
            case COOP_DISABLED -> " (co-op is off: aurora.awf.multiprofile.coop.enabled, RESTART_REQUIRED)";
            case MIGRATION_REQUIRED -> " (the store is format 1: /awf multiprofile migrate dry-run, then migration.mode: apply and restart)";
            case NOT_COOP -> " (the owner converts the profile first: /coop convert)";
            case ALREADY_COOP -> " (conversion is one way and already done)";
            case NOT_A_MEMBER -> " (only members of the co-op can do this)";
            case ALREADY_MEMBER -> " (that player is already a member)";
            case COOP_FULL -> " (see aurora.awf.multiprofile.coop.max-members)";
            case INVITE_EXPIRED -> " (ask for a new invitation)";
            case INVITE_NOT_FOUND -> " (see /coop invites)";
            case INVITE_EXISTS -> " (that player already has an open invitation to this co-op)";
            case ROLE_FORBIDDEN -> " (your role does not allow it)";
            case MEMBER_ONLINE -> " (the member plays this co-op on another server, or could not be switched out of it safely here)";
            case OWNER_ACTION_REFUSED -> " (the owner cannot leave, be kicked or transfer ownership)";
            case WRONG_NODE -> " (OD-1: a co-op is played and changed on the server that holds its world; join that server)";
            case VOTE_NOT_FOUND -> " (no undecided vote with that id; it may have expired)";
            case VOTE_OPEN -> " (one kick vote at a time per co-op)";
            case ELECTORATE_CHANGED -> " (the members changed; start a new vote)";
            case TARGET_NOT_INACTIVE -> " (in a two-member co-op see aurora.awf.multiprofile.coop.kick-vote.inactive-days)";
            case COOP_SWITCH_UNAVAILABLE -> " (members play a co-op only while authoritative login applies: aurora.awf.multiprofile.login.authoritative auto with co-op enabled, or true)";
            case ADAPTER_REQUIRED_FAILED -> " (a plugin's required profile data failed to capture or apply; nothing was skipped)";
            case ADAPTER_MISSING -> " (the profile holds required data of a plugin that is not loaded here at that version)";
            case HEAD_UNREADABLE -> " (the committed profile state could not be read; vanilla playerdata is never used instead)";
            case SWITCH_IN_PROGRESS -> " (a switch or the login restore is still running; retry in a moment)";
            case SLOT_LIMIT -> " (a co-op membership uses one profile slot)";
            case TYPE_NOT_ALLOWED -> " (see aurora.awf.multiprofile.coop.allowed-types)";
            case NAME_INVALID -> " (1-32 printable characters, no surrounding spaces)";
            case UNKNOWN_OUTCOME -> " (the store did not confirm the write; check before retrying)";
            case CAPACITY_EXCEEDED -> " (a bound is reached: open invitations, votes, salvage records, permanent receipts, or no free slot for a delivery)";
            case ACTION_FORBIDDEN_BY_TYPE -> " (the profile's type does not allow this action)";
            case PLAYER_OFFLINE -> " (the player must be online here with the profile active)";
            case RECOVERY_REQUIRED -> " (a pending operation is resolved at the next login or start)";
            case OWNERSHIP_CONFLICT -> " (the account or profile is busy on another server; retry later)";
            default -> "";
        };
    }

    private static String arg(final String[] args, final int index) {
        return args.length > index ? args[index] : null;
    }

    private static UUID uuid(final CommandSender s, final String typed, final String usage) {
        if (typed == null) {
            s.sendMessage(text("Usage: " + usage, SourbyCraftColors.WARNING));
            return null;
        }
        try {
            return UUID.fromString(typed);
        } catch (final IllegalArgumentException notUuid) {
            s.sendMessage(text("Not a UUID: " + typed + ". Usage: " + usage, SourbyCraftColors.DANGER));
            return null;
        }
    }

    /** An online player's exact name, a player name in the server's profile cache, or a UUID. Never blocks on a lookup. */
    private static UUID playerArg(final CommandSender s, final String typed, final String usage) {
        if (typed == null) {
            s.sendMessage(text("Usage: " + usage, SourbyCraftColors.WARNING));
            return null;
        }
        final Player online = Bukkit.getPlayerExact(typed);
        if (online != null) return online.getUniqueId();
        try {
            return UUID.fromString(typed);
        } catch (final IllegalArgumentException notUuid) {
            final OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(typed);
            if (cached != null) return cached.getUniqueId();
            s.sendMessage(text("Unknown player " + typed + "; use their UUID", SourbyCraftColors.DANGER));
            return null;
        }
    }

    /**
     * A player's name for a reply without touching the disk: the online player, else the server's in-memory
     * name cache ({@code usercache.json} as loaded), else the UUID. Replies run on the sender's entity scheduler, so a
     * player-data read ({@code OfflinePlayer#getName} falls back to one) is not acceptable here.
     */
    private static String nameOf(final UUID player) {
        final Player online = Bukkit.getPlayer(player);
        if (online != null) return online.getName();
        try {
            final net.minecraft.server.MinecraftServer server = net.minecraft.server.MinecraftServer.getServer();
            if (server != null) {
                final var cached = server.services().nameToIdCache().get(player);
                if (cached.isPresent() && !cached.get().name().isEmpty()) return cached.get().name();
            }
        } catch (final RuntimeException unavailable) {
            // no cache (a test harness): the UUID below
        }
        return player.toString();
    }

    private static String shortId(final UUID id) {
        return id.toString().substring(0, 8);
    }

    private static String minutesLeft(final long expiresAtMillis) {
        final long minutes = Math.max(0L, (expiresAtMillis - System.currentTimeMillis()) / 60_000L);
        return minutes + " min";
    }

    @Override
    public List<String> tabComplete(final CommandSender sender, final String alias, final String[] args) {
        if (args.length == 1) {
            final String lower = args[0].toLowerCase(Locale.ROOT);
            return SUBCOMMANDS.stream().filter(sub -> sub.startsWith(lower)).toList();
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("vote")) return List.of("yes", "no");
        if (args.length == 2 && args[0].equalsIgnoreCase("bank")) return List.of("transfer", "collections");
        if (args.length == 3 && args[0].equalsIgnoreCase("bank") && args[1].equalsIgnoreCase("transfer")) return List.of("to-personal", "to-shared");
        if (args.length == 2 && args[0].equalsIgnoreCase("salvage")) return List.of("claim");
        return List.of();
    }
}
