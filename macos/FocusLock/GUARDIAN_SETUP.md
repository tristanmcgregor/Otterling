# Guardian Account Setup

Otterling's tamper-resistance is a **social/account boundary**, not a purely technical one: the
daemon rejects `removeBlockedApp`, `removeBlockedDomain`, `disableDNSEnforcement`, and
`setCloudFilterEnabled(false)` from any account that isn't in the macOS `admin` group. That only
works if your day-to-day account genuinely
*isn't* an admin, and the one admin account's password is genuinely out of your reach.

This doc is the one-time setup to get there, plus the honest limits of the model.

## 1. Create the Guardian account

1. **System Settings > Users & Groups > Add Account...**
2. Account type: **Administrator**.
3. Name it something neutral (e.g. "Guardian"), set a password **the trusted person chooses and
   types in themselves** -- don't let them tell it to you even temporarily.
4. Finish creation while logged in as your current (still-admin) account.

The Guardian sets and resets this password manually here in System Settings, and sets the phone
app's PIN by typing it in on the device directly.

## 2. Demote your own account to Standard

1. **System Settings > Users & Groups**, select your account.
2. Turn off "Allow user to administer this computer". This requires authenticating as an admin --
   have the Guardian type their password in at this step.
3. Log out and back in to your account to confirm you're now Standard (Spotlight search
   "Users & Groups" and check your account no longer says Admin).

From this point on, anything requiring admin authentication (installing certain software,
changing system-level settings, and critically, the daemon's admin-group check) needs the
Guardian physically present to type their password.

## 3. FileVault

If FileVault is enabled, check **System Settings > Privacy & Security > FileVault** and make sure
the Guardian account is listed as an enabled FileVault user (add it if not, again with them typing
the password themselves). Otherwise you could end up in a state where the only FileVault-enabled
account is your own, undermining the split.

## 4. Verify the split actually works

From your (now Standard) account, add and then try to remove a test domain:

```
focuslockctl add-domain example.com
focuslockctl remove-domain example.com
```

The remove should print `DENIED: Only the Guardian admin account can remove a blocked domain.`.
If it instead succeeds, the demotion in step 2 didn't take; recheck Users & Groups. (Clean up the
test entry once verified: have the Guardian run the remove command themselves.)

## 5. Install the lock-profile tripwire (optional, recommended)

While logged in as the Guardian, run `Scripts/install_lock_profile.command` once (see
`README.md`'s "Using it" section for prerequisites -- a `filter-server` deployment with
`lockprofile_service.py` running, and its `LOCKPROFILE_TOKEN`). It hands off a `.mobileconfig` to
System Settings for the Guardian to approve. Read §6 immediately below before relying on this for
anything -- it is a tamper *tripwire*, not a stronger version of the account split above.

## 6. What this does and doesn't protect against

**Does protect against:** removing a blocked app or domain from your normal login, including via
the GUI, `focuslockctl`, or a raw XPC call from any code you write -- the check is on the daemon
side against your real uid, not anything the client claims. Blocking itself is unconditional and
permanent once added: there's no timer to wait out, only removal by the Guardian lifts it.

**One more removal path, added since this doc was first written**: the guardian dashboard
(`filter-server/dashboard/`) can also apply a removal remotely, via `DashboardConfigSync`
(README.md's "Dashboard-driven configuration"). That path is authorized by possession of the
server's `LOCKPROFILE_TOKEN` bearer, not the local admin-group check or passcode above -- a
deliberate tradeoff (see `/home/admin/.claude/plans/inherited-beaming-church.md`) accepted
because that token was already extractable from the shipped binary and posting spurious alerts.
It's still fully audited via `TamperReporter`.

**Does not protect against**, because no software running under an admin-controlled OS can:

- **A routine `sudo` command that never touches the daemon's XPC surface at all.** The admin-group
  check above only guards `removeBlockedApp`/`removeBlockedDomain`/`disableDNSEnforcement`/
  `setCloudFilterEnabled(false)` -- it says nothing about `sudo launchctl bootout
  system/app.otterling.helperd` (unloads the daemon outright),
  `sudo rm /Library/Application\ Support/FocusLock/state.json` or the embedded LaunchDaemon plist,
  `sudo pfctl -d`, or `networksetup -setdnsservers <service> Empty`. Anyone who already has the
  Guardian's admin password can run any of these from a normal Terminal, no reboot required -- this
  is meaningfully easier than everything else in this list, not a variant of it. Two detection (not
  prevention) layers narrow this: the lock profile (see `install_lock_profile.py`) moves DNS
  enforcement into a configuration profile, and the daemon reports it within ~15s if that profile
  is ever removed or the DNS it sets is overridden; a separate watchdog LaunchDaemon detects and
  reports the main daemon being unloaded. **Be clear about what the profile does and doesn't buy:**
  it carries `RemovalPasscode`, but on macOS that passcode is not effective against a local admin
  -- verified against Apple's own documentation, an admin can remove any profile by holding Option
  and clicking Remove in the Profiles pane and authenticating with their own admin password,
  passcode not required. `PayloadRemovalDisallowed` only becomes genuinely un-removable-by-anyone
  when a profile is delivered by an actual MDM server, which this project does not set up. So the
  profile here is a tripwire, not a lock: it makes tampering *loud*, it doesn't make tampering
  *impossible* for someone who already has the Guardian's password. Neither this nor the watchdog
  stops `rm`/`pfctl` run directly by someone who already has root -- both only make it get noticed.
- **Recovery Mode / Internet Recovery / an external boot drive.** Booting off another volume can
  reinstall macOS or run `rm`/`csrutil` against the daemon's files entirely outside this system.
  A real firmware password (T2/Apple Silicon "Lock Boot"), or on a Hackintosh whatever your
  motherboard's BIOS supports, is the only mitigation, and it's the Guardian's password to set,
  not yours.
- **Single-user mode / `-s` boot argument**, for the same reason.
- **Disabling SIP.** If System Integrity Protection is off (common on Hackintoshes for certain
  kext patches), a root process has materially fewer restrictions on modifying system state.
  Keep SIP on if your hardware allows it; if it can't be, treat that as a known gap.
- **Reformatting the disk.** The nuclear option always exists for whoever has physical access to
  the machine and a way to boot something else.

None of this is unique to Otterling -- it's true of every self-control tool that runs as software
on hardware you administer. The Guardian-account split raises the bar from "one click in Settings"
to "needs another person's cooperation, or a deliberate act of reinstalling the OS," which is the
realistic ceiling for this kind of tool.
