# Otterling — handover & lock-in checklist

This is a self-accountability system for the person who set it up ("the user"). Its strength
depends on the **accountability partner / server owner** holding the controls the user must not be
able to reach. Until the steps below are done, the user can still undo their own controls.

Hardware reality: the user's Mac is a **T1 Intel MacBook Pro** — it cannot be supervised/MDM-locked
(needs T2 or Apple Silicon). So the model is **detection + alert, not prevention**: a determined
admin can still wipe via Recovery, but every tamper is reported to a server the user does not
control. That server-side separation is the whole point of this checklist.




---

## A. Server owner — lock the user out of the backend

The user had **temporary root/SSH access** to `vpn.bartholomew.help` (192.168.0.254) to set this up.
The separation is only real once that access is removed. Run these as the owner:

1. **Revoke the user's SSH access.**
   ```bash
   # Inspect who can log in, then remove the user's key(s).
   sudo cat /home/admin/.ssh/authorized_keys
   sudo cp /home/admin/.ssh/authorized_keys /root/authorized_keys.bak
   # Remove the user's public key line(s), or replace the file with only keys you control:
   sudo nano /home/admin/.ssh/authorized_keys
   ```
2. **Revoke sudo / shell if the user had their own account.**
   ```bash
   sudo grep -R "" /etc/sudoers /etc/sudoers.d/        # review for the user's entries
   # remove any that grant the user sudo; consider disabling their login shell.
   ```
3. **Change any shared password** the user knew (the `admin` account password, etc.).
4. **Look for leftovers the user could use to get back in** (this is the trust-but-verify step):
   ```bash
   sudo crontab -l; sudo ls -la /etc/cron.d /etc/systemd/system | grep -vi otterling
   sudo grep -R "authorized_keys" /etc/ssh/sshd_config
   sudo last -20                                        # recent logins
   ```

## B. Server owner — verify the enforcement stack is intact

The user had root during setup, so confirm nothing was weakened:

```bash
cd /home/admin/Otterling/filter-server
docker compose ps                         # adguardhome, mitmproxy, dns-classifier, lockprofile, updates all Up
docker compose logs --tail=5 lockprofile  # should show it listening; "FCM push enabled" appears on first tamper event
```
- **Fleet** (fleet.bartholomew.help): the tamper policy exists and the user's Mac is enrolled and checking in.
- **Alert path**: `/alerts/tamper` (Bearer `LOCKPROFILE_TOKEN`) and `/alerts/fleet-webhook` (query secret) both live; failing events reach the partner's phone by SMS and (if subscribed) ntfy.
- **Anthropic credits**: the API key in `/var/lib/otterling/ci/secrets.env` powers BOTH the release AI-review gate AND the live NSFW AI classifier (`ai_classifier.py`). If it's out of credits, the classifier **fails open** (stops AI-blocking) and releases can't be reviewed. Keep it funded.

## C. Release pipeline note

- Pushes to `main` are AI-reviewed on the host before any APK reaches the phone — this is what stops
  the user from quietly shipping a protection-weakening change. **Keep the AI review enabled.**
- A one-time `--force-publish` was used to ship 0.1.43 while credits were out; that was per-invocation
  and AI review is already back on for the next push.
- Two CI scripts were patched (bugfixes, backups alongside): `anthropic_review_stream.py.bak`
  (was crashing on API errors) and `deploy_filter_server.sh.bak` (now excludes `lockprofile-data/`
  from the destructive rsync so deploys don't wipe the FCM key / alert log).

---

## D. Partner — hold the macOS Guardian passcode

On the user's Mac, the partner (not the user) sets and remembers:
```bash
otterlingctl set-passcode
```
This makes every protection-reducing action require **admin group AND this passcode**, applied
immediately once authorized. On the user's single-admin Mac, the admin check passes automatically,
so this passcode is the real gate — which is why the user must not know it.

## E. User — finish provisioning on the Mac

1. System Settings → General → Login Items & Extensions → **Otterling → Allow in the Background: ON**
   (makes the daemon run persistently and enforce 24/7).
2. Run `macos/FocusLock/Scripts/install_lock_profile.command`, enter admin password, then approve the
   profile in System Settings → Privacy & Security → Profiles (DNS floor + removal tripwire).
3. On the phone: Settings → App updates → Check for update → install 0.1.43 → open the app once
   (registers its FCM token for instant tamper alerts).

## F. Verify end-to-end (do once, together)

With the partner watching for the SMS alert:
- Stop the Mac daemon or remove the app while online → Fleet failing-policy → SMS.
- Remove the lock profile → `lock_profile_removed` → SMS.
- Connect a VPN on the Mac → `vpn_active` → SMS.

If those three land, the detection-and-alert loop is complete.
