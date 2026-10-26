param(
    [string]$FlutterPath = 'flutter',
    [string]$JdkPath,
    [switch]$SkipTests
)

$ErrorActionPreference = 'Stop'
$previousJavaHome = $env:JAVA_HOME
$previousGradleOptions = $env:GRADLE_OPTS
$projectDirectory = Split-Path -Parent $PSScriptRoot

Push-Location $projectDirectory
try {
    $flutterExecutable = (Get-Command $FlutterPath -ErrorAction Stop).Source
    $dartExecutable = Join-Path (Split-Path -Parent $flutterExecutable) 'dart.bat'
    if ($JdkPath) {
        $resolvedJdk = (Resolve-Path -LiteralPath $JdkPath).Path
        if (-not (Test-Path -LiteralPath (Join-Path $resolvedJdk 'bin/javac.exe'))) {
            throw "JDK compiler not found in $resolvedJdk"
        }
        $env:JAVA_HOME = $resolvedJdk
        # Flutter prefers Android Studio's bundled Java. Override Gradle's daemon
        # for this invocation without changing the user's Flutter configuration.
        $env:GRADLE_OPTS = "$previousGradleOptions `"-Dorg.gradle.java.home=$resolvedJdk`""
    }
    if ($env:HTTPS_PROXY) {
        $proxyUri = [Uri]$env:HTTPS_PROXY
        if ($proxyUri.Scheme -eq 'http' -and -not $proxyUri.UserInfo) {
            $env:GRADLE_OPTS += " -Dhttps.proxyHost=$($proxyUri.Host) -Dhttps.proxyPort=$($proxyUri.Port) -Dhttp.proxyHost=$($proxyUri.Host) -Dhttp.proxyPort=$($proxyUri.Port)"
        }
    }
    # Kotlin incremental caches cannot relativize C: Pub files against an E: project.
    # Apply this only to this invocation, keeping other projects' settings intact.
    $pubCacheDirectory = if ($env:PUB_CACHE) { $env:PUB_CACHE } else { Join-Path $env:LOCALAPPDATA 'Pub/Cache' }
    if ([System.IO.Path]::GetPathRoot($pubCacheDirectory) -ne [System.IO.Path]::GetPathRoot($projectDirectory)) {
        $env:GRADLE_OPTS += ' -Dorg.gradle.project.kotlin.incremental=false'
    }

    & $flutterExecutable pub get
    if ($LASTEXITCODE -ne 0) { throw 'Flutter dependency resolution failed' }

    if (-not $SkipTests) {
        & $dartExecutable format --output=none --set-exit-if-changed lib test
        if ($LASTEXITCODE -ne 0) { throw 'Dart formatting check failed' }

        & $flutterExecutable analyze --no-pub --fatal-infos
        if ($LASTEXITCODE -ne 0) { throw 'Flutter static analysis failed' }

        & $flutterExecutable test --no-pub --reporter expanded
        if ($LASTEXITCODE -ne 0) { throw 'Flutter tests failed' }
    }

    & $flutterExecutable build apk --release --target-platform android-arm64 --no-pub
    if ($LASTEXITCODE -ne 0) { throw 'Flutter APK build failed' }

    if (-not $SkipTests) {
        # Flutter writes Windows drive paths without escaping the colon. Java
        # accepts them, but Android lint requires canonical .properties escaping.
        $localPropertiesPath = Join-Path $projectDirectory 'android/local.properties'
        $localProperties = Get-Content -Raw -Encoding utf8 -LiteralPath $localPropertiesPath
        $localProperties = [regex]::Replace($localProperties, '(?m)^((?:sdk\.dir|flutter\.sdk)=[A-Za-z]):', '$1\:')
        [System.IO.File]::WriteAllText($localPropertiesPath, $localProperties, [System.Text.UTF8Encoding]::new($false))

        & (Join-Path $projectDirectory 'android/gradlew.bat') -p android :app:testReleaseUnitTest :app:lintRelease -Ptarget-platform=android-arm64 --console=plain --no-daemon
        if ($LASTEXITCODE -ne 0) { throw 'Android tests or lint failed' }
    }

    $apk = Join-Path $projectDirectory 'build/app/outputs/flutter-apk/app-release.apk'
    Get-Item -LiteralPath $apk | Select-Object FullName, Length
    Get-FileHash -LiteralPath $apk -Algorithm SHA256
} finally {
    $env:JAVA_HOME = $previousJavaHome
    $env:GRADLE_OPTS = $previousGradleOptions
    Pop-Location
}
