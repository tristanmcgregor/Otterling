"""Shared trigger-word list + matcher for content-block *reporting* (not blocking).

This is the server-side mirror of the Android app's `GuardianAlertSettings.DEFAULT_TRIGGER_WORDS`
(app/src/main/java/app/otterling/alerts/GuardianAlertSettings.kt) and the macOS
`FocusLockShared/TriggerWords.swift` list -- three copies of one list, kept in sync by hand the
same way the two adult-domain blocklists are mirrored between phone and server. The Android list is
the source of truth; port additions/removals from there.

Purpose: a page can be *blocked* for many reasons (a bare domain-list hit, an AI verdict), but the
Guardian only wants a phone alert when an actual trigger word was seen on the page -- see
`block_reporter.py` and where `mitm_nsfw_addon.py` calls it. Matching is word-boundary (`\\bword\\b`)
on lower-cased text, identical to the phone's `Regex("\\b...\\b")`, so "porn" doesn't fire on
"popcorn" and "milf" doesn't fire inside a longer alphanumeric run.
"""

from __future__ import annotations

import re

# Mirror of GuardianAlertSettings.DEFAULT_TRIGGER_WORDS (Android). Keep sorted-ish/grouped the same
# way for easy diffing against the Kotlin/Swift copies.
TRIGGER_WORDS = [
    "porn",
    "pornstar",
    "xxx video",
    "hardcore sex",
    "nude cams",
    "hentai",
    "nude photos",
    "adult video",
    "cam girls",
    "live sex cams",
    "amateur porn",
    "onlyfans",
    "pornhub",
    "xvideos",
    "xnxx",
    "redtube",
    "youporn",
    "xhamster",
    "spankbang",
    "motherless",
    "chaturbate",
    "brazzers",
    "bangbros",
    "2g1c",
    "alabama hot pocket",
    "anilingus",
    "autoerotic",
    "ball gag",
    "bareback",
    "barely legal",
    "bdsm",
    "beastiality",
    "bestiality",
    "big tits",
    "blowjob",
    "blow job",
    "blue waffle",
    "bondage",
    "bukkake",
    "camgirl",
    "camslut",
    "camwhore",
    "cleveland steamer",
    "clitoris",
    "creampie",
    "cumshot",
    "cunnilingus",
    "deepthroat",
    "deep throat",
    "dildo",
    "doggystyle",
    "doggy style",
    "dominatrix",
    "double penetration",
    "ejaculation",
    "erotica",
    "fellatio",
    "fisting",
    "futanari",
    "gangbang",
    "gang bang",
    "gokkun",
    "golden shower",
    "hardcore porn",
    "incest porn",
    "jailbait",
    "jizz",
    "lolita",
    "masturbate",
    "masturbating",
    "masturbation",
    "milf",
    "missionary position",
    "nympho",
    "nymphomania",
    "orgy",
    "paedophile",
    "pedophile",
    "pegging",
    "prostitute",
    "rimjob",
    "semen",
    "sex tape",
    "sexcam",
    "squirting",
    "strapon",
    "strap on",
    "swinger",
    "threesome",
    "upskirt",
    "voyeur",
    "webcam sex",
    "cybersex",
]

# One combined word-boundary regex, compiled once. Longer phrases first so e.g. "hardcore porn"
# is preferred over "porn" when both would match at the same spot (re.findall/search returns the
# leftmost match; ordering only affects which alternative wins at a given position).
_PATTERN = re.compile(
    r"\b(?:" + "|".join(re.escape(w) for w in sorted(TRIGGER_WORDS, key=len, reverse=True)) + r")\b",
    re.IGNORECASE,
)


def find_trigger(text: str | None) -> str | None:
    """Return the first trigger word found in `text` (lower-cased), or None. Cheap enough to call on
    every block decision."""
    if not text:
        return None
    match = _PATTERN.search(text)
    return match.group(0).lower() if match else None
