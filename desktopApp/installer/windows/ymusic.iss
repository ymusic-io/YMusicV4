; Inno Setup script for YMusic, compiled as-is by the Gradle task `packageReleaseInnoSetup`, which
; passes the version, paths and arch as ISCC /D defines.
;
; Build input (jpackage's `createReleaseDistributable` output):
;   AppSourceDir = desktopApp/build/compose/binaries/main-release/app/YMusic
;     containing YMusic.exe + runtime/ + app/
;
; Output:
;   OutputDir/OutputBaseFilename.exe (self-contained installer, LZMA2)

#define AppName "YMusic"
#define AppExeName "YMusic.exe"
; AppId stays stable across releases so the installer upgrades in place instead of installing
; side-by-side. Same UUID as the jpackage MSI's upgradeUuid for continuity across installer
; migrations (anyone upgrading from a jpackage MSI first uninstalls that; this installer then
; takes over the slot).
#define AppId "{{6F3C2B9E-4D1A-4E8B-9A57-2C1F0D8E7B34}"

[Setup]
AppId={#AppId}
AppName={#AppName}
AppVersion={#AppVersion}
AppVerName={#AppName} {#AppVersion}
AppPublisher={#AppPublisher}
AppPublisherURL={#AppURL}
AppSupportURL={#AppURL}
AppUpdatesURL={#AppURL}
DefaultDirName={autopf}\{#AppName}
DefaultGroupName={#AppName}
; auto = show the chooser on fresh install (user picks where to go), hide it on upgrade
; (same path as the detected previous install — swapping it mid-upgrade breaks tracking).
DisableProgramGroupPage=auto
DisableDirPage=auto
AllowNoIcons=yes
OutputDir={#OutputDir}
OutputBaseFilename={#OutputBaseName}
SetupIconFile={#IconFile}
UninstallDisplayIcon={app}\{#AppExeName}
Compression=lzma2/max
SolidCompression=yes
WizardStyle=modern
; Per-machine install into Program Files, requires UAC — same as the previous MSI. Switch to
; `lowest` and `DefaultDirName={localappdata}\Programs\{#AppName}` for a Chrome-style per-user
; install that skips the elevation prompt.
PrivilegesRequired=admin
; Arch: x64compatible, or arm64 for the native Windows on ARM build.
ArchitecturesAllowed={#Arch}
ArchitecturesInstallIn64BitMode={#Arch}
MinVersion=10.0
CloseApplications=force
RestartApplications=no

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"; Flags: unchecked

; An upgrade replaces the app image wholesale: jars and DLLs the new version dropped must not linger.
[InstallDelete]
Type: filesandordirs; Name: "{app}\app"
Type: filesandordirs; Name: "{app}\runtime"

[Files]
Source: "{#AppSourceDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{group}\{#AppName}"; Filename: "{app}\{#AppExeName}"
Name: "{autodesktop}\{#AppName}"; Filename: "{app}\{#AppExeName}"; Tasks: desktopicon

[Run]
Filename: "{app}\{#AppExeName}"; Description: "{cm:LaunchProgram,{#StringChange(AppName, '&', '&&')}}"; Flags: nowait postinstall skipifsilent
