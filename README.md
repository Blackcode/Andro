# Andro

A cross-platform **.NET MAUI** app (C#, .NET 10) that runs from a single project on:

| Platform | Target framework | Build from |
|---|---|---|
| Android | `net10.0-android` | Windows, macOS, Linux |
| iOS | `net10.0-ios` | macOS (Xcode required) |
| macOS (Mac Catalyst) | `net10.0-maccatalyst` | macOS (Xcode required) |
| Windows | `net10.0-windows10.0.19041.0` | Windows |

## Prerequisites

- [.NET 10 SDK](https://dotnet.microsoft.com/download)
- The MAUI workload: `dotnet workload install maui`
  (on Linux only `maui-android` is available)
- Android: Android SDK and JDK 17 (Visual Studio / VS Code's .NET MAUI extension can install them)
- iOS / Mac Catalyst: a Mac with the Xcode version your MAUI release needs

## Build and run

```bash
dotnet build Andro/Andro.csproj -f net10.0-android
dotnet build Andro/Andro.csproj -f net10.0-android -t:Run          # deploy to emulator/device
dotnet build Andro/Andro.csproj -f net10.0-ios -t:Run              # macOS only
dotnet build Andro/Andro.csproj -f net10.0-maccatalyst -t:Run      # macOS only
dotnet build Andro/Andro.csproj -f net10.0-windows10.0.19041.0 -t:Run   # Windows only
```

Or open `Andro.sln` in Visual Studio / Rider, or the folder in VS Code with the .NET MAUI extension.

## Project layout

```
Andro.sln
Andro/
  Andro.csproj        single multi-targeted project
  MauiProgram.cs      app bootstrap (DI, fonts, logging)
  App.xaml(.cs)       application and global resources
  AppShell.xaml(.cs)  Shell navigation
  MainPage.xaml(.cs)  first page
  Platforms/          per-platform entry points (Android, iOS, MacCatalyst, Windows)
  Resources/          app icon, splash, fonts, images, raw assets, styles
```

The app id is `com.blackcode.andro` (set in `Andro/Andro.csproj`).
