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
#define MediaProgId "YMusic.Media"
; The app's AppUserModelID (WindowsAppId.ID): the media flyout and taskbar find the app's name and
; icon through the shortcut carrying it, and a pin only merges with the running window when they match.
#define AppUserModelId "io.ymusic.windows"
; Never change: Inno finds the previous install by AppId, so a new one would install side by side.
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
ChangesAssociations=yes
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

[Registry]
; Lists the app under Open With without taking any type's default.
Root: HKA; Subkey: "Software\Classes\{#MediaProgId}"; ValueType: string; ValueData: "Media file"; Flags: uninsdeletekey
Root: HKA; Subkey: "Software\Classes\{#MediaProgId}\DefaultIcon"; ValueType: string; ValueData: "{app}\{#AppExeName},0"
Root: HKA; Subkey: "Software\Classes\{#MediaProgId}\shell\open\command"; ValueType: string; ValueData: """{app}\{#AppExeName}"" ""%1"""
Root: HKA; Subkey: "Software\Classes\.mp3\OpenWithProgids"; ValueType: string; ValueName: "{#MediaProgId}"; ValueData: ""; Flags: uninsdeletevalue
Root: HKA; Subkey: "Software\Classes\.m4a\OpenWithProgids"; ValueType: string; ValueName: "{#MediaProgId}"; ValueData: ""; Flags: uninsdeletevalue
Root: HKA; Subkey: "Software\Classes\.m4b\OpenWithProgids"; ValueType: string; ValueName: "{#MediaProgId}"; ValueData: ""; Flags: uninsdeletevalue
Root: HKA; Subkey: "Software\Classes\.aac\OpenWithProgids"; ValueType: string; ValueName: "{#MediaProgId}"; ValueData: ""; Flags: uninsdeletevalue
Root: HKA; Subkey: "Software\Classes\.flac\OpenWithProgids"; ValueType: string; ValueName: "{#MediaProgId}"; ValueData: ""; Flags: uninsdeletevalue
Root: HKA; Subkey: "Software\Classes\.wav\OpenWithProgids"; ValueType: string; ValueName: "{#MediaProgId}"; ValueData: ""; Flags: uninsdeletevalue
Root: HKA; Subkey: "Software\Classes\.ogg\OpenWithProgids"; ValueType: string; ValueName: "{#MediaProgId}"; ValueData: ""; Flags: uninsdeletevalue
Root: HKA; Subkey: "Software\Classes\.oga\OpenWithProgids"; ValueType: string; ValueName: "{#MediaProgId}"; ValueData: ""; Flags: uninsdeletevalue
Root: HKA; Subkey: "Software\Classes\.opus\OpenWithProgids"; ValueType: string; ValueName: "{#MediaProgId}"; ValueData: ""; Flags: uninsdeletevalue
Root: HKA; Subkey: "Software\Classes\.mka\OpenWithProgids"; ValueType: string; ValueName: "{#MediaProgId}"; ValueData: ""; Flags: uninsdeletevalue
Root: HKA; Subkey: "Software\Classes\.mp4\OpenWithProgids"; ValueType: string; ValueName: "{#MediaProgId}"; ValueData: ""; Flags: uninsdeletevalue
Root: HKA; Subkey: "Software\Classes\.m4v\OpenWithProgids"; ValueType: string; ValueName: "{#MediaProgId}"; ValueData: ""; Flags: uninsdeletevalue
Root: HKA; Subkey: "Software\Classes\.mov\OpenWithProgids"; ValueType: string; ValueName: "{#MediaProgId}"; ValueData: ""; Flags: uninsdeletevalue
Root: HKA; Subkey: "Software\Classes\.webm\OpenWithProgids"; ValueType: string; ValueName: "{#MediaProgId}"; ValueData: ""; Flags: uninsdeletevalue
Root: HKA; Subkey: "Software\Classes\.mkv\OpenWithProgids"; ValueType: string; ValueName: "{#MediaProgId}"; ValueData: ""; Flags: uninsdeletevalue

[Icons]
Name: "{group}\{#AppName}"; Filename: "{app}\{#AppExeName}"; AppUserModelID: "{#AppUserModelId}"
Name: "{autodesktop}\{#AppName}"; Filename: "{app}\{#AppExeName}"; AppUserModelID: "{#AppUserModelId}"; Tasks: desktopicon

[Run]
Filename: "{app}\{#AppExeName}"; Description: "{cm:LaunchProgram,{#StringChange(AppName, '&', '&&')}}"; Flags: nowait postinstall skipifsilent
