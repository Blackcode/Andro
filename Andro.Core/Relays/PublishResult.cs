namespace Andro.Core.Relays;

public sealed record PublishResult(string Relay, bool Accepted, string Message);
