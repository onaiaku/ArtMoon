; ArtMoon — gamepad-first streaming client, pairs with ArtLight on the host.
; SourceDir is the self-contained runtime built by build-arch.bat +
; manual windeployqt (see CLAUDE.md §3).
#define AppName "ArtMoon"
#define AppVersion "1.9.1"
#define AppPublisher "onaiaku"
#define AppURL "https://github.com/onaiaku/ArtMoon"
#define AppExeName "ArtMoon.exe"
; ⚠️ The second place the version is written, and the only one kept by hand: the exe
; takes it from app\version.txt through app.pro (VERSION and VERSION_STR), the installer
; does not. Bump both, or the setup ships as the previous version and names its own
; file ArtMoon_<old>_Installer, with nothing to notice it by.
#define SourceDir "build\deploy-x64-release"

; ── The USB/IP engine ────────────────────────────────────────────────────────
; ArtMoon cannot share a USB device by itself. It reads `usbipd list` and runs
; `usbipd bind` through the input service, so usbipd-win is a hard dependency, not
; an optional extra — and until now it was an unstated one: a PC without it got an
; empty USB tab and no route out. It is carried here the way ArtLight carries its
; USB/IP client, and the version and hash are pinned in the build workflow because
; this file is executed with administrator rights on a user's machine.
;
; Carried only when the build actually fetched it. A local ISCC run without the
; payload compiles to an installer with no Device sharing task, rather than one
; whose tick box silently does nothing — the same rule the ArtLight bootstrapper
; holds itself to with HasEmbeddedControlPayload().
; ⚠️ No backslash may appear inside an ISPP string literal. ISPP escapes the quoting
; character (and C-style escapes are live), so "build\vendor\" is a broken string and
; "build\vendor" is a vertical tab. Only the backslash-free filename is built here; the
; directory is written out at each of the two places that need it — the FileExists probe
; below (forward slashes, which Win32 accepts) and the Inno-level Source/Parameters
; strings, where a backslash is native and safe.
#define UsbipdVersion "5.3.0"
#define UsbipdMsi "usbipd-win_" + UsbipdVersion + "_x64.msi"
; Hard stop, not a graceful skip. An installer compiled without the payload has no Device
; sharing task, so every PC that has not already got usbipd-win keeps the dead end this
; payload exists to remove — and nothing about the installer would say so. The build
; workflow fetches and hash-checks the file before compiling, so this only ever fires for a
; local ISCC run that has not done that, which is exactly when someone needs telling.
#if !FileExists(AddBackslash(SourcePath) + "build/vendor/" + UsbipdMsi)
  #error USB/IP payload missing. Fetch usbipd-win into build/vendor/ first - see the Fetch the bundled USB/IP engine step in .github/workflows/build-windows-installer.yml
#endif

[Setup]
AppId={{B7A2C3D4-E5F6-7890-ABCD-EF1234567890}
AppName={#AppName}
AppVersion={#AppVersion}
UninstallDisplayName={#AppName}
AppPublisher={#AppPublisher}
AppPublisherURL={#AppURL}
AppSupportURL={#AppURL}
AppUpdatesURL={#AppURL}
DefaultDirName={autopf}\{#AppName}
DefaultGroupName={#AppName}
InfoBeforeFile=changelog.txt
SetupIconFile=installer\resources\artmoon.ico
WizardSmallImageFile=installer\resources\artmoon.png
WizardImageFile=installer\resources\artmooninstaller.png
UninstallDisplayIcon={app}\{#AppExeName}
AllowNoIcons=yes
DirExistsWarning=no
CloseApplications=yes
Compression=lzma2
SolidCompression=yes
OutputDir=build\installer
OutputBaseFilename=ArtMoon_{#AppVersion}_Installer
WizardStyle=modern
DisableWelcomePage=no
MinVersion=10.0
; 64-bit Setup binary (Inno Setup 7+). ArtMoon.exe is x64, so a 32-bit installer
; bought nothing; this also gets high-entropy ASLR by default. Note this drops
; Windows 10 on ARM64, which only emulates x86 — but the x64 app could never have
; run there anyway. Windows 11 on ARM64 emulates x64 and is unaffected.
SetupArchitecture=x64
; x64compatible (unlike StreamTweak's x64os): this is the CLIENT, and an ARM64 device
; running it under x64 emulation is a plausible scenario.
ArchitecturesInstallIn64BitMode=x64compatible
ArchitecturesAllowed=x64compatible

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"

[Messages]
WelcomeLabel1=Welcome to the ArtMoon Setup Wizard
WelcomeLabel2=

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"
Name: "xboxtile"; Description: "Add an icon to the Xbox app's 'My apps' section"; GroupDescription: "{cm:AdditionalIcons}"
; checkedonce, not checked: ticked by default because a PC without usbipd-win has no working
; USB tab at all — the common case is the one that needs it. It stays unticked for anyone
; who has turned it off, so an upgrade never quietly reverses their choice.
Name: "usbipdwin"; Description: "USB/IP device sharing support (usbipd-win) — required to share devices with ArtLight"; GroupDescription: "Device sharing:"; Flags: checkedonce

[Files]
; portable.dat excluded: it would force Qt to write settings/cache to {app}
; (= Program Files), where standard users can't write. Default Qt storage
; (HKCU + %LOCALAPPDATA%) is user-writable and used instead.
; cache\* excluded: the app writes an auto-updated gamecontrollerdb.txt there at
; runtime, so a dev machine that ran the deployed build before packaging would
; otherwise ship its own stale copy (the pristine one is installed from the root,
; two lines below). Runtime folders must never be swept into the installer —
; StreamTweak shipped a WebView2 cache this way for six releases.
; *.bat excluded: the deploy directory is where throwaway launchers get dropped while
; chasing a bug, and a build machine's scratch scripts must never reach a user.
Source: "{#SourceDir}\*"; DestDir: "{app}"; \
    Excludes: "*.log,*.bat,sl_*.txt,artmoon_pad.log,portable.dat,cache\*"; \
    Flags: ignoreversion recursesubdirs createallsubdirs
Source: "{#SourceDir}\gamecontrollerdb.txt"; DestDir: "{app}"; Flags: ignoreversion
Source: "installer\resources\artmoon.png"; Flags: dontcopy
Source: "changelog.txt"; DestDir: "{app}"; Flags: ignoreversion
; {tmp} and deleteafterinstall: this is an installer, not something ArtMoon needs at
; runtime. Setup's own [Run] entries execute elevated, which is what usbipd-win needs —
; it installs a service, two drivers and its own TCP 3240 firewall rule — the last of
; which is scoped to the local subnet, so [Run] adds one of ours that also works over
; a VPN. See the entries at the end of this section for why.
Source: "build\vendor\{#UsbipdMsi}"; DestDir: "{tmp}"; Flags: deleteafterinstall

[Icons]
Name: "{group}\{#AppName}"; Filename: "{app}\{#AppExeName}"
Name: "{group}\{cm:UninstallProgram,{#AppName}}"; Filename: "{uninstallexe}"
Name: "{autodesktop}\{#AppName}"; Filename: "{app}\{#AppExeName}"; Tasks: desktopicon

; No [Registry] section on purpose. There used to be an HKCU entry creating
; Software\FoggyBytes\StreamLight with uninsdeletekey, but nothing ever wrote to that
; key. From 5.4.0 the app's settings lived under Software\FoggyBytes\StreamLight
; (main.cpp; before 5.4.0 they were under Software\Moonlight Game Streaming Project\
; Moonlight, shared with Moonlight itself); as of 1.2.0 they live under
; Software\ArtMoon\ArtMoon, with the 1.2.0 launch migrating the old key across
; (storemigration.cpp) — and the section still must not come back:
;
;   · uninsdeletekey on the real settings key would make a reinstall lose paired hosts
;     and preferences;
;   · this installer runs elevated, so HKCU here resolves to the ELEVATING account's
;     hive, which may not be the interactive user's. Anything the setup wrote would land
;     in the wrong place — which is why the store change in 5.4.0 is done by the app at
;     startup and never by the installer. Same trap the [Run] section below avoids with
;     runasoriginaluser.

[Run]
; If the user opted in to "Add an icon to the Xbox app's My apps", seed the
; CustomLibraryManagement manifest with the ArtMoon entry + branded tile
; PNG. Runs hidden, blocking, finishes in ~50 ms.
; runasoriginaluser is critical: PrivilegesRequired defaults to "admin" so
; the installer is elevated, and a vanilla [Run] would inherit the elevated
; token. The child's %LOCALAPPDATA% would then resolve to the elevating
; account's profile, NOT the interactive user's — registerEntry() would
; write the manifest in the wrong place where Xbox app never reads.
Filename: "{app}\{#AppExeName}"; Parameters: "--register-xbox-tile"; \
    Tasks: xboxtile; \
    Flags: runhidden waituntilterminated runasoriginaluser
; This entry is also how the in-app update (app/backend/appupdate.cpp) reopens the app: the
; update runs this setup visibly, with no switches, so its last page carries this box. There
; used to be a second, silent-only entry gated on a /SELFUPDATE switch; it went when the update
; stopped running Setup /VERYSILENT (14/09/2026).
Filename: "{app}\{#AppExeName}"; Description: "{cm:LaunchProgram,{#AppName}}"; \
    Flags: nowait postinstall skipifsilent
; Gated on the Device sharing task. No runasoriginaluser here, unlike the Xbox tile above:
; usbipd-win installs drivers and a firewall rule and genuinely needs the elevated token.
; /qn and /norestart keep it silent inside our own wizard; an already-installed copy is a
; no-op, and MSI refuses a downgrade rather than replacing a newer one.
Filename: "{sys}\msiexec.exe"; Parameters: "/i ""{tmp}\{#UsbipdMsi}"" /qn /norestart"; \
    StatusMsg: "Installing USB/IP device sharing support..."; \
    Tasks: usbipdwin; \
    Flags: runhidden waituntilterminated
; Device sharing needs a rule that works off the LAN. usbipd-win makes its own for TCP
; 3240, but scopes it to Scope="localSubnet": a peer on the far side of a VPN is not on
; a local subnet, so the far end is refused and sharing fails over the tunnel with
; nothing on screen to explain it. Delete-then-add, because netsh refuses a duplicate
; name and an upgrade always finds the rule the previous install left behind. The
; delete is deliberately ungated — a machine that has stopped asking for device sharing
; should not keep the rule — and neither entry uses RunOnceId, which would stop the pair
; refreshing on an upgrade. Both are allowed to fail: Inno only complains when a program
; cannot be launched, not when it exits non-zero.
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall delete rule name=""ArtMoon USB/IP"""; \
    Flags: runhidden waituntilterminated
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall add rule name=""ArtMoon USB/IP"" dir=in action=allow protocol=TCP localport=3240 profile=any remoteip=any"; \
    Tasks: usbipdwin; \
    Flags: runhidden waituntilterminated

[UninstallRun]
; Remove the input service before its files go. Stopping is allowed to fail — a service
; that was never started, or already stopped, is not an error worth anyone's attention.
; RunOnceId is required once a section has more than one entry.
Filename: "{sys}\sc.exe"; Parameters: "stop ArtMoonInputService"; \
    Flags: runhidden waituntilterminated; RunOnceId: "StopInputService"
Filename: "{sys}\sc.exe"; Parameters: "delete ArtMoonInputService"; \
    Flags: runhidden waituntilterminated; RunOnceId: "DeleteInputService"
; Our own 3240 rule (added in [Run] above; usbipd-win does not clean it up for us).
; Unconditional: deleting a rule that is not there is not an error.
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall delete rule name=""ArtMoon USB/IP"""; \
    Flags: runhidden waituntilterminated; RunOnceId: "DeleteUsbIpFirewallRule"

[Code]
// ── The privileged input service ─────────────────────────────────────────────
// ArtMoon cannot bind a USB device for itself: `bind` is refused without administrator
// rights. This service does it on the user's behalf, over its own local pipe — which is
// also why nothing about it needs a command line in front of the user, and why setting
// it up can be a silent part of Setup. See docs/usb-ip-input-passthrough.md.
//
// Create-then-start, not configure: `sc create` fails when the service already exists,
// and an upgrade always finds one, so any previous service is stopped and removed first.
// Every one of those calls is allowed to fail — on a first install there is nothing to
// stop, and that is not an error. Inno only complains when a program cannot be launched,
// not when it exits non-zero, so no error handling is needed to get that behaviour.
procedure InstallInputService;
var
  ResultCode: Integer;
  ServiceExe: String;
  ServiceKey: String;
  Attempt: Integer;
  Created: Boolean;
begin
  ServiceExe := ExpandConstant('{app}\artmoon-input-service.exe');
  if not FileExists(ServiceExe) then
    Exit;

  Exec(ExpandConstant('{sys}\sc.exe'), 'stop ArtMoonInputService', '',
       SW_HIDE, ewWaitUntilTerminated, ResultCode);
  Exec(ExpandConstant('{sys}\sc.exe'), 'delete ArtMoonInputService', '',
       SW_HIDE, ewWaitUntilTerminated, ResultCode);

  // Removing a service is asynchronous, so `sc create` immediately afterwards can fail with
  // "marked for deletion". Retry rather than let the helper end up not installed at all.
  ResultCode := 1;
  Created := False;
  Attempt := 0;
  while (not Created) and (Attempt < 10) do
  begin
    Created := Exec(ExpandConstant('{sys}\sc.exe'),
                    'create ArtMoonInputService binPath= "' + ServiceExe + '" start= auto',
                    '', SW_HIDE, ewWaitUntilTerminated, ResultCode) and (ResultCode = 0);
    if not Created then
      Sleep(500);
    Attempt := Attempt + 1;
  end;

  if Created then
  begin
    // sc.exe cannot store a quoted path. Measured on a real machine: `sc create` and `sc config`
    // both strip the quotes, and passing them escaped makes sc print its usage and refuse. So the
    // path is corrected underneath sc, where the string is written verbatim and SCM reads it back
    // correctly. An unquoted path containing spaces is the well-known opening where a local user
    // drops C:\Program.exe and gets SYSTEM.
    ServiceKey := 'SYSTEM\CurrentControlSet\Services\ArtMoonInputService';
    RegWriteStringValue(HKLM, ServiceKey, 'ImagePath', '"' + ServiceExe + '"');

    // Ordering. Without this the helper may start about five seconds into boot, while usbipd does
    // not come up for minutes - and it then never appears. Only added when usbipd is really there:
    // a dependency on a service that is not installed stops ours from starting at all, which is a
    // worse failure than the one it fixes.
    if RegKeyExists(HKLM, 'SYSTEM\CurrentControlSet\Services\usbipd') then
      Exec(ExpandConstant('{sys}\sc.exe'), 'config ArtMoonInputService depend= usbipd', '',
           SW_HIDE, ewWaitUntilTerminated, ResultCode);

    // And if it does start and then dies, let SCM bring it back instead of leaving it dead until
    // somebody notices the Settings page says the helper is not running.
    Exec(ExpandConstant('{sys}\sc.exe'),
         'failure ArtMoonInputService reset= 86400 actions= restart/30000/restart/30000/restart/30000',
         '', SW_HIDE, ewWaitUntilTerminated, ResultCode);

    Exec(ExpandConstant('{sys}\sc.exe'), 'start ArtMoonInputService', '',
         SW_HIDE, ewWaitUntilTerminated, ResultCode);
  end;
end;

procedure CurStepChanged(CurStep: TSetupStep);
begin
  if CurStep = ssPostInstall then
    InstallInputService;
end;

// A running service holds its own executable open, so an upgrade cannot replace the file
// until it is stopped. This hook runs before the file copy — the only point early enough
// to matter. Returning an empty string means carry on, not abort.
function PrepareToInstall(var NeedsRestart: Boolean): String;
var
  ResultCode: Integer;
begin
  Exec(ExpandConstant('{sys}\sc.exe'), 'stop ArtMoonInputService', '',
       SW_HIDE, ewWaitUntilTerminated, ResultCode);
  Result := '';
end;

// ── Stop Windows from maximising the wizard ──────────────────────────────────
// On a handheld — the ROG Ally is where this shows up — the wizard opens filling
// the whole screen, with the layout still drawn for a small window: the artwork
// at its natural size in the top-left corner and a large empty area around it.
// That is the signature of a window MAXIMISED after its layout was computed, not
// of a wizard computed too large (which would stretch the artwork to full height).
// It is not the Inno Setup version: it did the same under Inno Setup 6.
//
// Windows only auto-maximises windows that can be maximised, so the fix is to say
// this one cannot: take the sizing frame and the maximise box off it. Setup's
// wizard is not meant to be resized anyway — Inno Setup 7 dropped WizardResizable
// for exactly that reason, which makes this a no-op there and a fix everywhere else.
//
// ⚠️ SetWindowLongW, not SetWindowLongPtrW. Both are exported by user32 on 64-bit,
// but the Ptr variant takes a LONG_PTR (8 bytes) and we build a 64-bit installer
// (SetupArchitecture=x64), so handing it a 32-bit value is the argument-size
// mismatch Inno Setup 7's release notes warn about. Window styles are 32-bit, so
// the plain variant is the correct one for GWL_STYLE on any architecture.
const
  GWL_STYLE        = -16;
  WS_MAXIMIZEBOX   = $00010000;
  WS_THICKFRAME    = $00040000;
  SWP_NOSIZE       = $0001;
  SWP_NOMOVE       = $0002;
  SWP_NOZORDER     = $0004;
  SWP_NOACTIVATE   = $0010;
  SWP_FRAMECHANGED = $0020;

function GetWindowLong(hWnd: HWND; nIndex: Integer): LongInt;
  external 'GetWindowLongW@user32.dll stdcall';
function SetWindowLong(hWnd: HWND; nIndex: Integer; dwNewLong: LongInt): LongInt;
  external 'SetWindowLongW@user32.dll stdcall';
function SetWindowPos(hWnd: HWND; hWndInsertAfter: HWND; X, Y, cx, cy: Integer; uFlags: Cardinal): LongInt;
  external 'SetWindowPos@user32.dll stdcall';

procedure MakeWizardFixedSize;
begin
  SetWindowLong(WizardForm.Handle, GWL_STYLE,
    GetWindowLong(WizardForm.Handle, GWL_STYLE) and not (WS_MAXIMIZEBOX or WS_THICKFRAME));

  // Required after any style change: SetWindowLong alters the style bits but leaves the
  // cached non-client frame alone, so the window keeps the client area computed for the old
  // styles until Windows is asked to recompute it. SetWindowLong's own documentation
  // prescribes this call.
  //
  // ⚠️ It is NOT the fix for the check boxes being clipped on their left edge — that was my
  // first theory and hardware disproved it. That symptom appears only on the Ally and is
  // unexplained; see §28. This call stays because it is correct on its own terms.
  SetWindowPos(WizardForm.Handle, 0, 0, 0, 0, 0,
    SWP_NOMOVE or SWP_NOSIZE or SWP_NOZORDER or SWP_NOACTIVATE or SWP_FRAMECHANGED);
end;

var
  LogoImage: TBitmapImage;
  DevelopedByLabel: TNewStaticText;
  GitHubLinkLabel: TNewStaticText;
  ArtLightPage: TWizardPage;
  ArtLightIntroLabel: TNewStaticText;
  ArtLightBulletsLabel: TNewStaticText;
  ArtLightOutroLabel: TNewStaticText;
  ArtLightLearnMoreLabel: TNewStaticText;
  ArtLightLinkLabel: TNewStaticText;

procedure GitHubLinkClick(Sender: TObject);
var
  ErrorCode: Integer;
begin
  ShellExec('open', '{#AppURL}', '', '', SW_SHOWNORMAL, ewNoWait, ErrorCode);
end;

procedure ArtLightLinkClick(Sender: TObject);
var
  ErrorCode: Integer;
begin
  ShellExec('open', 'https://github.com/onaiaku/ArtLight', '', '', SW_SHOWNORMAL, ewNoWait, ErrorCode);
end;

procedure InitializeWizard;
var
  TmpFileName: String;
begin
  // Before anything is laid out: the window exists by now, but has not been shown.
  MakeWizardFixedSize;

  ExtractTemporaryFile('artmoon.png');
  TmpFileName := ExpandConstant('{tmp}\artmoon.png');

  LogoImage := TBitmapImage.Create(WizardForm);
  LogoImage.Parent := WizardForm.WelcomePage;
  // PngImage (not Bitmap) is the loader for .png — see Inno Setup's CodeClasses example.
  LogoImage.PngImage.LoadFromFile(TmpFileName);
  LogoImage.Left := WizardForm.WelcomeLabel1.Left;
  LogoImage.Top := WizardForm.WelcomeLabel1.Top + WizardForm.WelcomeLabel1.Height + ScaleY(25);
  // Sized explicitly, NOT with AutoSize.
  //
  // AutoSize draws the PNG at its native pixel size, so the artwork's own resolution silently
  // becomes the layout. That held while the file happened to be 96x96; the moment it was
  // replaced with a 672x672 master the logo rendered seven times too big and bled across the
  // welcome page. Stretch scales whatever it is given into the box below, so the asset can be
  // any resolution — and a higher one is now the better choice, since it downscales cleanly.
  //
  // ScaleX/ScaleY where AutoSize gave raw pixels: the rest of this layout is already
  // DPI-scaled, so the logo was the one element that shrank on a high-DPI display.
  LogoImage.AutoSize := False;
  LogoImage.Stretch := True;
  LogoImage.Width := ScaleX(96);
  LogoImage.Height := ScaleY(96);

  DevelopedByLabel := TNewStaticText.Create(WizardForm);
  DevelopedByLabel.Parent := WizardForm.WelcomePage;
  DevelopedByLabel.Left := LogoImage.Left;
  DevelopedByLabel.Top := LogoImage.Top + LogoImage.Height + ScaleY(30);
  DevelopedByLabel.Caption := 'Developed by onaiaku & Rias © 2026';
  DevelopedByLabel.Font.Size := 10;
  DevelopedByLabel.AutoSize := True;

  GitHubLinkLabel := TNewStaticText.Create(WizardForm);
  GitHubLinkLabel.Parent := WizardForm.WelcomePage;
  GitHubLinkLabel.Left := DevelopedByLabel.Left;
  GitHubLinkLabel.Top := DevelopedByLabel.Top + DevelopedByLabel.Height + ScaleY(15);
  GitHubLinkLabel.Caption := '{#AppURL}';
  GitHubLinkLabel.Cursor := crHand;
  GitHubLinkLabel.Font.Color := clHighlight;
  GitHubLinkLabel.Font.Style := [fsUnderline];
  GitHubLinkLabel.OnClick := @GitHubLinkClick;

  // Dedicated wizard page for ArtLight — full inner-page width gives the
  // bullet list room to breathe (the Welcome page's right panel is too narrow).
  ArtLightPage := CreateCustomPage(wpWelcome,
    'ArtLight — recommended companion app', #13#10 +
    'Install ArtLight on the host PC to unlock ArtMoon''s advanced features.');

  ArtLightIntroLabel := TNewStaticText.Create(ArtLightPage);
  ArtLightIntroLabel.Parent := ArtLightPage.Surface;
  ArtLightIntroLabel.Left := 0;
  ArtLightIntroLabel.Top := 0;
  ArtLightIntroLabel.Width := ArtLightPage.SurfaceWidth;
  ArtLightIntroLabel.WordWrap := True;
  ArtLightIntroLabel.AutoSize := True;
  ArtLightIntroLabel.Caption :=
    'ArtMoon works as a standalone streaming client. When paired with ArtLight — ' +
    'a free open-source host for your gaming PC, developed by onaiaku — ' +
    'it gains the following advanced features:';

  ArtLightBulletsLabel := TNewStaticText.Create(ArtLightPage);
  ArtLightBulletsLabel.Parent := ArtLightPage.Surface;
  ArtLightBulletsLabel.Left := ScaleX(16);
  ArtLightBulletsLabel.Top := ArtLightIntroLabel.Top + ArtLightIntroLabel.Height + ScaleY(14);
  ArtLightBulletsLabel.AutoSize := True;
  ArtLightBulletsLabel.Caption :=
    // NB: this label has no WordWrap, so every bullet must stay on one line —
    // keep them at or under ~76 characters or they get clipped on the right.
    '•  Game library sync with cover art and store badges' + #13#10 +
    '•  Live host metrics (GPU, encoder, VRAM, temperature, CPU, network)' + #13#10 +
    '•  Session quality grading, and the host''s last session on your Home' + #13#10 +
    '•  Wake the host and sign in with its PIN, from the sofa, on the pad' + #13#10 +
    '•  Live bitrate shown against your configured target on the host dashboard' + #13#10 +
    '•  Remote host sleep, restart, power-off and Windows Update' + #13#10 +
    '•  Tailscale presence for remote streaming over the internet';

  ArtLightOutroLabel := TNewStaticText.Create(ArtLightPage);
  ArtLightOutroLabel.Parent := ArtLightPage.Surface;
  ArtLightOutroLabel.Left := 0;
  ArtLightOutroLabel.Top := ArtLightBulletsLabel.Top + ArtLightBulletsLabel.Height + ScaleY(18);
  ArtLightOutroLabel.Width := ArtLightPage.SurfaceWidth;
  ArtLightOutroLabel.WordWrap := True;
  ArtLightOutroLabel.AutoSize := True;
  ArtLightOutroLabel.Caption :=
    'ArtLight is optional — you can install it on the host PC at any time, ' +
    'no need to interrupt this setup. Click Next to continue installing ArtMoon.';

  ArtLightLearnMoreLabel := TNewStaticText.Create(ArtLightPage);
  ArtLightLearnMoreLabel.Parent := ArtLightPage.Surface;
  ArtLightLearnMoreLabel.Left := 0;
  ArtLightLearnMoreLabel.Top := ArtLightOutroLabel.Top + ArtLightOutroLabel.Height + ScaleY(16);
  ArtLightLearnMoreLabel.Caption := 'Learn more:';
  ArtLightLearnMoreLabel.AutoSize := True;

  ArtLightLinkLabel := TNewStaticText.Create(ArtLightPage);
  ArtLightLinkLabel.Parent := ArtLightPage.Surface;
  ArtLightLinkLabel.Left := ArtLightLearnMoreLabel.Left + ArtLightLearnMoreLabel.Width + ScaleX(4);
  ArtLightLinkLabel.Top := ArtLightLearnMoreLabel.Top;
  ArtLightLinkLabel.Caption := 'https://github.com/onaiaku/ArtLight';
  ArtLightLinkLabel.Cursor := crHand;
  ArtLightLinkLabel.Font.Color := clHighlight;
  ArtLightLinkLabel.Font.Style := [fsUnderline];
  ArtLightLinkLabel.OnClick := @ArtLightLinkClick;
  ArtLightLinkLabel.AutoSize := True;
end;