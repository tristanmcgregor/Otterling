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

    public init(command: String, result: ElevatedCommandResult) {
        self.command = command
        self.result = result
    }
}

public struct AssistantActionResult: Codable {
    public let translationExplanation: String
    public let steps: [AssistantStep]

    public init(translationExplanation: String, steps: [AssistantStep]) {
        self.translationExplanation = translationExplanation
        self.steps = steps
    }
}
