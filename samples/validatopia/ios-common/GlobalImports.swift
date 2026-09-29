// multipaz-swiftui's sources expect the Kotlin framework's types without an import of their own.
@_exported @preconcurrency import ValidatopiaShared

// The iOS 27 SDK adds SwiftUI.Document, which makes multipaz's Document ambiguous in every file that
// also imports SwiftUI. A declaration in this module takes precedence over both imports.
public typealias Document = ValidatopiaShared.Document
