# Guardian Account Setup

FocusLock's tamper-resistance is a **social/account boundary**, not a purely technical one: the
daemon rejects `removeBlockedApp`, `removeBlockedDomain`, and `endSessionEarly` from any account
that isn't in the macOS `admin` group. That only works if your day-to-day account genuinely
*isn't* an admin, and the one admin account's password is genuinely out of your reach.

This doc is the one-time setup to get there, plus the honest limits of the model.

## 1. Create the Guardian account

1. **System Settings > Users & Groups > Add Account...**
2. Account type: **Administrator**.
3. Name it something neutral (e.g. "Guardian"), set a password **the trusted person chooses and
   types in themselves** -- don't let them tell it to you even temporarily.
4. Finish creation while logged in as your current (still-admin) account.

### 1b. Setting/resetting the Guardian password remotely

If the Guardian isn't physically present (or you want a way to have them reset the password
later without visiting), `focuslockctl guardian-link`/`guardian-claim` do this over a one-time
link instead -- see `server/README.md` at the repo root for the full flow. In short: you run
`focuslockctl guardian-link <relay-url> <phone-pubkey>` to get a link, send it to the Guardian,
they open it and type a password (creating the account if it doesn't exist yet, or resetting it if
it does) and a phone PIN, and their browser encrypts each separately before it ever reaches the
relay server -- so even if you administer that server yourself, you only ever see ciphertext.
`focuslockctl guardian-claim` then fetches and applies the Mac's half; the phone app claims its own
half independently. You never see the plaintext at any point in this flow.

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

## 5. What this does and doesn't protect against

**Does protect against:** removing a blocked app or domain from your normal login, including via
the GUI, `focuslockctl`, or a raw XPC call from any code you write -- the check is on the daemon
side against your real uid, not anything the client claims. Blocking itself is unconditional and
permanent once added: there's no timer to wait out, only removal by the Guardian lifts it.

**Does not protect against**, because no software running under an admin-controlled OS can:

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

None of this is unique to FocusLock -- it's true of every self-control tool that runs as software
on hardware you administer. The Guardian-account split raises the bar from "one click in Settings"
to "needs another person's cooperation, or a deliberate act of reinstalling the OS," which is the
realistic ceiling for this kind of tool.
