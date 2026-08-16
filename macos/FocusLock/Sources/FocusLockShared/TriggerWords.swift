import Foundation

/// Shared trigger-word list + matcher for on-screen content *reporting* (not blocking).
///
/// macOS mirror of the Android app's `GuardianAlertSettings.DEFAULT_TRIGGER_WORDS`
/// (app/src/main/java/app/otterling/alerts/GuardianAlertSettings.kt) and the server's
/// `filter-server/trigger_words.py` -- three hand-synced copies of one list, the same way the two
/// adult-domain blocklists are mirrored between phone and server. The Android list is the source of
/// truth; port additions/removals from there.
///
/// Used by `FocusLockScanner` (the accessibility scanner), which walks the frontmost browser's
/// accessibility tree, and reports a `trigger_word_detected` event via `TamperReporter` when a
/// listed word appears on screen -- the macOS equivalent of the phone's `FocusGuardAccessibilityService`
/// trigger-word scan. Report-only, never blocks.
///
/// Matching is word-boundary (`\bword\b`) on lower-cased text, identical to the phone's
/// `Regex("\b...\b")`, so "porn" doesn't fire on "popcorn".
public enum TriggerWords {
    /// Mirror of `GuardianAlertSettings.DEFAULT_TRIGGER_WORDS`. Order is irrelevant for matching
    /// (the regex sorts by length itself); kept grouped like the Kotlin copy for easy diffing.
    public static let list: [String] = [
        "porn", "pornstar", "xxx video", "hardcore sex", "nude cams", "hentai", "nude photos",
        "adult video", "cam girls", "live sex cams", "amateur porn", "onlyfans", "pornhub",
        "xvideos", "xnxx", "redtube", "youporn", "xhamster", "spankbang", "motherless",
        "chaturbate", "brazzers", "bangbros", "2g1c", "alabama hot pocket", "anilingus",
        "autoerotic", "ball gag", "bareback", "barely legal", "bdsm", "beastiality", "bestiality",
        "big tits", "blowjob", "blow job", "blue waffle", "bondage", "bukkake", "camgirl",
        "camslut", "camwhore", "cleveland steamer", "clitoris", "creampie", "cumshot",
        "cunnilingus", "deepthroat", "deep throat", "dildo", "doggystyle", "doggy style",
        "dominatrix", "double penetration", "ejaculation", "erotica", "fellatio", "fisting",
        "futanari", "gangbang", "gang bang", "gokkun", "golden shower", "hardcore porn",
        "incest porn", "jailbait", "jizz", "lolita", "masturbate", "masturbating", "masturbation",
        "milf", "missionary position", "nympho", "nymphomania", "orgy", "paedophile", "pedophile",
        "pegging", "prostitute", "rimjob", "semen", "sex tape", "sexcam", "squirting", "strapon",
        "strap on", "swinger", "threesome", "upskirt", "voyeur", "webcam sex", "cybersex",
    ]

    /// Compiled once: `\b(?:phrase|word|...)\b`, longest alternatives first so "hardcore porn" wins
    /// over "porn" at the same position. Case-insensitive.
    private static let regex: NSRegularExpression? = {
        let escaped = list
            .sorted { $0.count > $1.count }
            .map { NSRegularExpression.escapedPattern(for: $0) }
            .joined(separator: "|")
        let pattern = "\\b(?:\(escaped))\\b"
        return try? NSRegularExpression(pattern: pattern, options: [.caseInsensitive])
    }()

    /// Returns the first trigger word found in `text` (lower-cased), or nil. Cheap enough for a
    /// per-scan haystack.
    public static func firstMatch(in text: String) -> String? {
        guard !text.isEmpty, let regex else { return nil }
        let range = NSRange(text.startIndex..<text.endIndex, in: text)
        guard let match = regex.firstMatch(in: text, options: [], range: range),
              let swiftRange = Range(match.range, in: text) else { return nil }
        return text[swiftRange].lowercased()
    }
}
