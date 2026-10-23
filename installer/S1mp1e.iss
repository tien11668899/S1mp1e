; S1mp1e installer (Inno Setup 6).
;
; Per-USER install (no admin / no UAC) so the launcher's own auto-update can run
; the new installer silently and upgrade in place. Build with:
;
;   ISCC.exe /DVersion=0.1.1 /DPublishDir="<abs path to dotnet publish output>" installer\S1mp1e.iss
;
; The publish output is a self-contained win-x64 build of the Avalonia app plus the
; bundled itest.exe (see scripts/build-installer.ps1).

#define AppName "S1mp1e"
#ifndef Version
  #define Version "0.0.0"
#endif
#ifndef PublishDir
  #define PublishDir "..\dist\publish"
#endif
#define ExeName "S1mp1e.exe"

[Setup]
; Stable AppId — never change it, or upgrades install side-by-side instead of over.
AppId={{9E5F1C2A-7B44-4E6E-9A1F-5D9C3B7A1E00}
AppName={#AppName}
AppVersion={#Version}
AppPublisher={#AppName}
AppPublisherURL=https://github.com/tien11668899/S1mp1e
DefaultDirName={autopf}\{#AppName}
DisableProgramGroupPage=yes
DisableDirPage=auto
PrivilegesRequired=lowest
PrivilegesRequiredOverridesAllowed=dialog
OutputDir=..\dist
OutputBaseFilename=S1mp1e-Setup-{#Version}
SetupIconFile=..\avalonia\Assets\s1mp1e.ico
UninstallDisplayIcon={app}\{#ExeName}
UninstallDisplayName={#AppName}
WizardStyle=modern
Compression=lzma2/max
SolidCompression=yes
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
; Let the launcher's silent auto-update replace the running exe via Restart Manager.
CloseApplications=yes
RestartApplications=no

[Languages]
; The whole wizard (and the uninstaller) in Traditional Chinese — Inno ships no zh-TW file, so it is ours, translated
; from this Inno version's Default.isl. Both .isl and .iss must stay UTF-8 WITH a BOM, or ISCC reads them as ANSI.
Name: "zhtw"; MessagesFile: "ChineseTraditional.isl"

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"

[Files]
; The whole self-contained publish tree (app + runtime + itest.exe).
Source: "{#PublishDir}\*"; DestDir: "{app}"; Flags: recursesubdirs createallsubdirs ignoreversion

[Icons]
Name: "{autoprograms}\{#AppName}"; Filename: "{app}\{#ExeName}"
Name: "{autodesktop}\{#AppName}"; Filename: "{app}\{#ExeName}"; Tasks: desktopicon

[Run]
; Both entries start the launcher THROUGH explorer.exe, never as Setup's own child: Setup runs with Redirection Guard
; (it refuses to follow junctions made by non-admin users), a child inherits that, and so would the game the launcher
; starts — Minecraft then cannot open the saves / resourcepacks junctions of its instance folder and crashes at start-up
; ("FileAlreadyExistsException ...\instances\<ver>\saves"). Explorer starts it like the Start menu does.
; Interactive install: offer to launch when the wizard finishes.
Filename: "{win}\explorer.exe"; Parameters: """{app}\{#ExeName}"""; Description: "啟動 {#AppName}"; Flags: nowait postinstall skipifsilent
; Silent auto-update: relaunch the app after replacing it.
Filename: "{win}\explorer.exe"; Parameters: """{app}\{#ExeName}"""; Flags: nowait skipifnotsilent
