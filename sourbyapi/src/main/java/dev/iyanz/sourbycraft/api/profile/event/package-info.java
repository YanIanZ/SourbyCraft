/**
 * Added in Build 55. Events for the lifecycle of AWF MultiProfile profiles: a switch about to start
 * ({@link dev.iyanz.sourbycraft.api.profile.event.ProfilePreSwitchEvent}), a committed switch
 * ({@link dev.iyanz.sourbycraft.api.profile.event.ProfileSwitchedEvent}), and a profile created
 * ({@link dev.iyanz.sourbycraft.api.profile.event.ProfileCreatedEvent}) or deleted
 * ({@link dev.iyanz.sourbycraft.api.profile.event.ProfileDeletedEvent}). They are fired only while MultiProfile is
 * running ({@code aurora.awf.multiprofile.enabled}, default false).
 *
 * <p><b>Thread.</b> Every event here is asynchronous ({@code isAsynchronous()} is true): it is fired from a thread of
 * the profile service, never from a region, entity, global-tick or network thread. A handler must not touch world,
 * entity or player state directly (schedule that through {@link dev.iyanz.sourbycraft.api.SourbyAPI#scheduler()},
 * for example {@code runFor(plugin, player, ...)}) and should return quickly.</p>
 *
 * <p><b>Delivery.</b> None of them is cancellable and none is a transaction: an event reports what the profile
 * service decided or committed and cannot change it. Each is fired once, by the node that made the change. A crash
 * between the change and its event loses the event; the profile store ({@code AuroraProfiles}) is the source of
 * truth, not the event stream.</p>
 */
package dev.iyanz.sourbycraft.api.profile.event;
