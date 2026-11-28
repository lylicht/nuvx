#define AppName "VYRAL"
#ifndef AppVersion
#define AppVersion "1.10.0"
#endif
#define AppExe "vyral-rust.exe"

[Setup]
AppId={{B4E9D81C-44DE-4A8C-9421-318EA8F25F56}
AppName={#AppName}
AppVersion={#AppVersion}
AppPublisher=night
AppPublisherURL=https://github.com/syrrnight/VYRAL
AppSupportURL=https://github.com/syrrnight/VYRAL/issues
DefaultDirName={autopf}\VYRAL\Rust
DefaultGroupName=VYRAL - Rust
DisableProgramGroupPage=yes
PrivilegesRequired=lowest
PrivilegesRequiredOverridesAllowed=dialog
ArchitecturesAllowed=x64compatible arm64
ArchitecturesInstallIn64BitMode=x64compatible arm64
MinVersion=10.0
OutputBaseFilename=VYRAL-Rust-Windows-{#AppVersion}
Compression=lzma2/ultra
SolidCompression=yes
WizardStyle=modern
SetupIconFile=..\assets\icon\icon.ico
UninstallDisplayIcon={app}\{#AppExe}
CloseApplications=yes

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"; Flags: unchecked

[Files]
Source: "..\..\target\x86_64-pc-windows-msvc\release\{#AppExe}"; DestDir: "{app}"; Flags: ignoreversion; Check: not IsArm64
Source: "..\..\target\aarch64-pc-windows-msvc\release\{#AppExe}"; DestDir: "{app}"; Flags: ignoreversion; Check: IsArm64
Source: "..\LICENSE"; DestDir: "{app}"; Flags: ignoreversion
Source: "..\assets\fonts\LICENSE-*.txt"; DestDir: "{app}\licenses"; Flags: ignoreversion
Source: "..\assets\licenses\*.txt"; DestDir: "{app}\licenses"; Flags: ignoreversion

[Icons]
Name: "{group}\{#AppName}"; Filename: "{app}\{#AppExe}"
Name: "{group}\Uninstall {#AppName}"; Filename: "{uninstallexe}"
Name: "{autodesktop}\{#AppName}"; Filename: "{app}\{#AppExe}"; Tasks: desktopicon

[Registry]
Root: HKA; Subkey: "Software\Classes\vyral-rust"; ValueType: string; ValueName: ""; ValueData: "URL:VYRAL Rust Protocol"; Flags: uninsdeletekey
Root: HKA; Subkey: "Software\Classes\vyral-rust"; ValueType: string; ValueName: "URL Protocol"; ValueData: ""
Root: HKA; Subkey: "Software\Classes\vyral-rust\DefaultIcon"; ValueType: string; ValueName: ""; ValueData: """{app}\{#AppExe}"",0"
Root: HKA; Subkey: "Software\Classes\vyral-rust\shell\open\command"; ValueType: string; ValueName: ""; ValueData: """{app}\{#AppExe}"" ""%1"""

[InstallDelete]
Type: files; Name: "{app}\portable.txt"

[Run]
Filename: "{app}\{#AppExe}"; Description: "{cm:LaunchProgram,{#AppName}}"; Flags: nowait postinstall skipifsilent
