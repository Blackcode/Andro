<#
.SYNOPSIS
  Creates the Android release signing key for Uncage and prepares it for GitHub.

.DESCRIPTION
  Every Uncage APK must be signed with the same key, or Android refuses to install it as an
  update (users would have to uninstall and lose their chats). Run this ONCE, on your own PC.

  It creates uncage-release.keystore next to this script's parent folder, then copies the
  keystore (base64) to the clipboard so you can paste it into a GitHub secret.

  Keep the .keystore file and its password somewhere safe and private (a password manager).
  If you lose them you can never publish an update to already installed apps.
  Never commit the keystore to git (it is in .gitignore).
#>
$ErrorActionPreference = "Stop"

function Find-Keytool {
	$inPath = Get-Command keytool -ErrorAction SilentlyContinue
	if ($inPath) { return $inPath.Source }
	if ($env:JAVA_HOME -and (Test-Path "$env:JAVA_HOME\bin\keytool.exe")) { return "$env:JAVA_HOME\bin\keytool.exe" }
	# Visual Studio installs Microsoft's OpenJDK here for Android development.
	$jdk = Get-ChildItem "$env:ProgramFiles\Microsoft\jdk-*", "$env:ProgramFiles\Android\openjdk\*", "$env:ProgramFiles\Eclipse Adoptium\*" -Directory -ErrorAction SilentlyContinue |
		Sort-Object Name -Descending | Where-Object { Test-Path "$($_.FullName)\bin\keytool.exe" } | Select-Object -First 1
	if ($jdk) { return "$($jdk.FullName)\bin\keytool.exe" }
	throw "keytool not found. Install the .NET MAUI workload in Visual Studio (it includes a JDK), or a JDK 17+."
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$keystore = Join-Path $repoRoot "uncage-release.keystore"
$alias = "uncage"

if (Test-Path $keystore) {
	throw "$keystore already exists. Don't replace an existing release key: apps signed with it could no longer be updated."
}

$keytool = Find-Keytool
Write-Host "Using $keytool"

$secure = Read-Host "Choose a strong keystore password (at least 8 characters)" -AsSecureString
$confirm = Read-Host "Repeat the password" -AsSecureString
$password = [Runtime.InteropServices.Marshal]::PtrToStringAuto([Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure))
$check = [Runtime.InteropServices.Marshal]::PtrToStringAuto([Runtime.InteropServices.Marshal]::SecureStringToBSTR($confirm))
if ($password -ne $check) { throw "Passwords don't match." }
if ($password.Length -lt 8) { throw "Password must be at least 8 characters." }

& $keytool -genkeypair -v -storetype PKCS12 -keystore $keystore -alias $alias -keyalg RSA -keysize 4096 -validity 10000 `
	-storepass $password -keypass $password -dname "CN=Uncage, O=Blackcode"
if ($LASTEXITCODE -ne 0) { throw "keytool failed." }

[Convert]::ToBase64String([IO.File]::ReadAllBytes($keystore)) | Set-Clipboard

Write-Host ""
Write-Host "Created $keystore" -ForegroundColor Green
Write-Host "The keystore (base64) is now on your clipboard." -ForegroundColor Green
Write-Host ""
Write-Host "Add these at GitHub: repository > Settings > Secrets and variables > Actions > New repository secret"
Write-Host "  ANDROID_KEYSTORE_BASE64   = (paste from clipboard)"
Write-Host "  ANDROID_KEYSTORE_PASSWORD = the password you just chose"
Write-Host "  ANDROID_KEY_ALIAS         = $alias"
Write-Host ""
Write-Host "Then back up $keystore and the password somewhere private, and clear your clipboard." -ForegroundColor Yellow
