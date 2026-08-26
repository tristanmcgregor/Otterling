import Foundation

/// A natural-language admin request for the GUI's "AI Assistant" box -- see
/// `AIAssistantClient.swift`'s doc comment for the full flow this feeds into.
public struct AssistantRequest: Codable {
    public let request: String

    public init(request: String) {
        self.request = request
    }
}

/// One command the assistant's translation produced, paired with the outcome of running it through
/// the EXACT same `SudoBroker` pipeline a manually-typed command goes through -- the assistant is a
/// convenience layer over the broker, never a way around it.
public struct AssistantStep: Codable {
    public let command: String
    public let result: ElevatedCommandResult
    /// The translator's stated reasoning for proposing this step, shown once per round (nil for
    /// every step after the first in a multi-command round) -- see `AssistantActionResult.stopReason`'s
    /// doc comment for the multi-round agent loop this is threaded through.
    public let roundExplanation: String?

    public init(command: String, result: ElevatedCommandResult, roundExplanation: String? = nil) {
        self.command = command
        self.result = result
        self.roundExplanation = roundExplanation
    }
}

public struct AssistantActionResult: Codable {
    public let translationExplanation: String
    public let steps: [AssistantStep]
    /// Why the agent loop stopped -- "done" (translator said nothing more was needed, the normal
    /// happy path), "no_commands" (the very first translation produced nothing to run), or
    /// "max_rounds"/"max_steps" (a hard cap in `XPCService.requestAssistantAction` was hit; these
    /// caps exist independent of anything the translator says, since nothing else stops a
    /// propose-execute-observe cycle from looping forever, e.g. re-proposing a just-denied command
    /// hoping for a different verdict).
    public let stopReason: String

    public init(translationExplanation: String, steps: [AssistantStep], stopReason: String = "done") {
        self.translationExplanation = translationExplanation
        self.steps = steps
        self.stopReason = stopReason
    }
}
